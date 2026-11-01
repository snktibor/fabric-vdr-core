#!/usr/bin/env python3
"""Publication hygiene: what must not reach a public repository or its Zenodo archive.

Run by `just hygiene` and by the `hygiene` CI workflow on every push. Checks of the
current tree fail the run. Checks of the history only warn, because history is not
rewritten; `--strict` makes them fail too, as an optional local check.

`--publish` is the publication gate: it adds the checks a repository must pass before it
goes public and gets its Zenodo release (guide: "Archiving the code on Zenodo"), such as
complete Zenodo metadata, a finished README, lockfiles and the release workflow. CI runs
it once PUBLISHING.md exists; copy scripts/publishing-checklist.md there to start.

Secrets are gitleaks' job (see .github/workflows/hygiene.yml); this script covers the rest.
"""
import argparse, datetime, json, os, re, shutil, subprocess, sys, unicodedata
from pathlib import Path

SELF = "scripts/hygiene.py"

# Patterns are POSIX extended regexes, because `git log -G` takes no Perl syntax.
# Places a reader of a public archive cannot open, or must not see before the conference.
PRIVATE_LINKS = (
    r"overleaf\.com/(project|read)"
    r"|github\.com/[A-Za-z0-9_.-]+/TDK[0-9][0-9]-"
    r"|github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]*notes([^A-Za-z0-9_-]|$)"
    r"|notion\.so|teams\.microsoft\.com|sharepoint\.com|1drv\.ms"
    r"|claude\.ai/(share|chat)|chatgpt\.com/(share|c)/"
)
LOCAL_PATHS = r"/home/[a-z_][a-z0-9_.-]*/|/Users/[A-Za-z0-9_.-]+/|[A-Za-z]:\\Users\\"
# Commit identities of coding agents. Co-Authored-By trailers are allowed; authorship is not.
AGENT_IDENTITY = r"noreply@anthropic\.com|\bclaude\b|openai|\bcodex\b|copilot|cursor(agent)?@|gemini|devin-ai"
AGENT_FILES = r"(^|/)(\.claude|\.codex|\.cursor|\.gemini|CLAUDE\.md|AGENTS\.md|GEMINI\.md)(/|$)"
# Paper sources belong in the paper repository; standalone TikZ figures are fine.
PAPER_SOURCE = r"\\documentclass"
STANDALONE = r"\\documentclass(\[[^]]*\])?\{standalone\}"

# --publish: what the Zenodo record and the README need (frozen list: publish-checks.md).
SUPERVISORS = ["Farkas, Martin", "Kocsis, Imre"]
PLACEHOLDER = r"TODO|<[^<>\n]+>|10\.xxxx"
# Zenodo descriptions are HTML, so a tag such as <i> or </p> is not a placeholder.
HTML_TAG = r"</?(a|b|i|u|p|em|strong|br|hr|ul|ol|li|code|pre|sup|sub|span|div|h[1-6]|blockquote|img|table|tr|td|th)(\s[^<>]*)?/?>"
ORCID = r"\d{4}-\d{4}-\d{4}-\d{3}[\dX]"
REQUIRED_FILES = {"README.md": r"README\.md", "LICENSE": r"LICEN[CS]E(\.\w+)?", ".zenodo.json": r"\.zenodo\.json",
                  "CITATION.cff": r"CITATION\.cff", "justfile": r"[jJ]ustfile", ".devcontainer/": r"\.devcontainer/.+"}
DOCKERFILE = r"(^|/)([^/]+\.)?Dockerfile$"
# A manifest is satisfied by any of its lockfiles in the same or an ancestor directory (workspaces).
LOCKFILES = {"Cargo.toml": ["Cargo.lock"],
             "package.json": ["package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml", "yarn.lock", "bun.lock", "bun.lockb"],
             "pyproject.toml": ["uv.lock", "poetry.lock", "pdm.lock"],
             "go.mod": ["go.sum"]}
SECRET_PATHS = r"(^|/)(\.env(\.[\w.-]+)?|wallets?[^/]*\.(txt|json)|id_rsa|[^/]*\.pem|[^/]*\.key|keystore[^/]*)$"
FINGERPRINT = r"([0-9a-f]{40}:)?[^:]+:[A-Za-z0-9_-]+:\d+"

findings = {"error": 0, "warning": 0}


def git(*args):
    r = subprocess.run(["git", *args], capture_output=True, text=True)
    if r.returncode not in (0, 1):  # 1: git grep found nothing
        sys.exit(f"hygiene: git {' '.join(args)} failed: {r.stderr.strip()}")
    return r.stdout


def blob(commit, path):
    """The file as of a commit, or as of its parent when that commit deleted it."""
    for rev in (commit, f"{commit}^"):
        r = subprocess.run(["git", "show", f"{rev}:{path}"], capture_output=True, text=True, errors="replace")
        if r.returncode == 0:
            return r.stdout
    return ""


def report(level, rule, msg, path=None, line=None):
    findings[level] += 1
    if os.environ.get("GITHUB_ACTIONS"):
        loc = f" file={path}" + (f",line={line}" if line else "") if path else ""
        print(f"::{level}{loc},title=hygiene/{rule}::{msg}")
    else:
        where = f"{path}:{line}: " if line else f"{path}: " if path else ""
        print(f"{level}[{rule}]: {where}{msg}")


def tree_grep(rule, rx, msg, pathspec=".", allow=None):
    out = git("grep", "-I", "-n", "-i", "-E", rx, "--", pathspec, f":!{SELF}")
    for hit in out.splitlines():
        path, line, text = hit.split(":", 2)
        if not (allow and re.search(allow, text)):
            report("error", rule, msg, path, line)


def history_grep(rule, rx, msg, level, exclude=None):
    # Commits whose diff adds or removes a matching line, on every branch.
    out = git("log", "--all", "-i", "-E", f"-G{rx}", "--format=@%h", "--name-only",
              "--", ".", f":!{SELF}")
    commit, seen = None, set()
    for l in out.splitlines():
        if l.startswith("@"):
            commit = l[1:]
        elif l and l not in seen:  # newest commit per path is enough to find it
            seen.add(l)
            if exclude and re.search(exclude, blob(commit, l)):
                continue  # for example a standalone figure, which may stay
            report(level, rule, f"{msg} (history, commit {commit})", l)


def read(path):
    return Path(path).read_text(errors="replace") if Path(path).is_file() else ""


def plain(name):
    """Lower case without accents, so that "Kocsis, Imre" matches "kocsis, imre"."""
    return unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode().lower().strip()


def readme_sections(text):
    """Headings (any level, HTML comments removed) mapped to the text below them."""
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    parts = re.split(r"^#{1,6}[ \t]+(.+)$", text, flags=re.M)
    return {parts[i].strip().lower(): parts[i + 1] for i in range(1, len(parts) - 1, 2)}


def check_files(tracked):
    for name, rx in REQUIRED_FILES.items():
        if not any(re.fullmatch(rx, f) for f in tracked):
            report("error", "missing-file", f"{name} is missing at the repository root (guide: app:code:github)")
    if not any(re.search(DOCKERFILE, f) for f in tracked):
        report("error", "missing-file", "no Dockerfile; the runtime image reproduces the results (guide: app:code:docker)")
    for d in ("src", "tests", "experiments"):
        if not any(f.startswith(d + "/") for f in tracked):
            report("warning", "layout", f"no {d}/ directory (guide layout: app:code:github)")
    just = read("justfile") or read("Justfile")
    recipes = (subprocess.run(["just", "--summary"], capture_output=True, text=True).stdout.split()
               if shutil.which("just") and just else re.findall(r"^([A-Za-z0-9_-]+)\b[^\n=]*:(?!=)", just, re.M))
    if just and "reproduce" not in recipes:
        report("error", "reproduce", "the justfile has no reproduce recipe; `just reproduce` regenerates every figure and table", "justfile")
    workflows = "\n".join(read(f) for f in tracked if f.startswith(".github/workflows/"))
    if not re.search(r"just reproduce --smoke", workflows):
        report("warning", "smoke", "no workflow runs `just reproduce --smoke` on every push")


def check_zenodo():
    try:
        z = json.loads(read(".zenodo.json") or "{}")
    except json.JSONDecodeError as e:
        return report("error", "zenodo", f"not valid JSON: {e}", ".zenodo.json")
    if not z:
        return {}
    for key in ("title", "upload_type", "description", "creators", "license", "version"):
        if not z.get(key):
            report("error", "zenodo", f"{key} is missing or empty", ".zenodo.json")
    for c in z.get("creators", []):
        if not (c.get("name") and c.get("affiliation")):
            report("error", "zenodo", f"creator {c.get('name', '?')!r} needs a name and an affiliation", ".zenodo.json")
    for c in z.get("creators", []) + z.get("contributors", []):
        if "orcid" in c and not re.fullmatch(ORCID, str(c["orcid"])):
            report("error", "zenodo", f"orcid {c['orcid']!r} of {c.get('name', '?')} is not an ORCID iD; fill it in or remove the field", ".zenodo.json")
    for m in sorted({m for m in re.findall(PLACEHOLDER, json.dumps(z, ensure_ascii=False))
                     if not re.fullmatch(HTML_TAG, m, re.I)}):
        report("error", "zenodo", f"placeholder {m!r} left; a placeholder fails the Zenodo release", ".zenodo.json")
    date = z.get("publication_date")
    if date is not None:
        try:
            datetime.date.fromisoformat(str(date))
        except ValueError:
            report("error", "zenodo", f"publication_date {date!r} is not a YYYY-MM-DD date", ".zenodo.json")
    for r in z.get("related_identifiers", []):
        ident = str(r.get("identifier", ""))
        if not (r.get("relation") and r.get("resource_type")) or not re.match(r"https?://\S+$|10\.\d{4,9}/\S+$", ident):
            report("error", "zenodo", f"related identifier {ident!r} needs a URL or DOI, a relation and a resource_type", ".zenodo.json")
    supervisors = {plain(c.get("name", "")) for c in z.get("contributors", []) if c.get("type") == "Supervisor"}
    for name in SUPERVISORS:
        if plain(name) not in supervisors:
            report("error", "supervisor", f'contributors must list {{"name": "{name}", "type": "Supervisor"}}', ".zenodo.json")
    return z


def check_citation(z):
    cff = read("CITATION.cff")
    if not (cff and z):
        return
    for key in ("title", "version", "license"):
        m = re.search(rf"^{key}:\s*(.+?)\s*$", cff, re.M)
        value = m.group(1).strip("'\"") if m else None
        if value is not None and str(z.get(key, "")) != value:
            report("error", "citation", f"{key} is {value!r} here but {z.get(key)!r} in .zenodo.json; keep them in line", "CITATION.cff")


def check_readme():
    text = read("README.md")
    if not text:
        return
    sections = readme_sections(text)
    if not re.search(r"doi\.org/10\.5281/zenodo\.\d+", text):
        report("warning", "readme", "no concept DOI badge yet; add it from the Zenodo record after the first release", "README.md")
    block = re.search(r"^```[^\n]*\n(.*?)^```", re.sub(r"<!--.*?-->", "", text, flags=re.S), re.S | re.M)
    if not (block and "docker run" in block.group(1) and "reproduce" in block.group(1)):
        report("error", "readme", "the first command must be `docker run ... reproduce` (guide: app:code:readme)", "README.md")
    elif "<you>" in block.group(1) or "<repo>" in block.group(1):
        report("error", "readme", "the first command still names ghcr.io/<you>/<repo>; name the results-tag image", "README.md")
    wanted = {"results map": r"results map", "reproduction limits": r"does not reproduce|reproduction limits|limits",
              "results tag": r"results tag", "how to cite": r"\bcite\b|citation", "license": r"licen[cs]e"}
    for what, rx in wanted.items():
        found = [body for head, body in sections.items() if re.search(rx, head)]
        if not found:
            report("error", "readme", f"no {what} section (guide: app:code:readme)", "README.md")
        elif what == "results map" and not re.search(r"^\s*\|", found[0], re.M):
            report("error", "readme", "the results map section has no table", "README.md")
        elif what == "results tag" and not re.search(r"\bv\d+\.\d+\.\d+\b", found[0]):
            report("error", "readme", "the results tag section names no vX.Y.Z tag", "README.md")
    for n, line in enumerate(text.splitlines(), 1):
        if "TODO" in line.replace("zenodo.TODO", ""):
            report("error", "readme", "unfinished text (TODO)", "README.md", n)


def check_docker_release(tracked):
    dockerfiles = [f for f in tracked if re.search(DOCKERFILE, f)]
    if dockerfiles and not any(re.search(r'^ENTRYPOINT\s*\[\s*"just"', read(f), re.M) for f in dockerfiles):
        report("warning", "docker", 'no Dockerfile has ENTRYPOINT ["just"] (guide: app:code:docker)')
    dev = read(".devcontainer/devcontainer.json")
    if dev and '"dockerfile"' not in dev:
        report("warning", "devcontainer", "the dev container is not built from the repository's Dockerfile", ".devcontainer/devcontainer.json")
    have = set(tracked)
    for f in tracked:
        name = f.rsplit("/", 1)[-1]
        if name not in LOCKFILES or (name == "go.mod" and "require" not in read(f)):
            continue
        parts = f.split("/")[:-1]
        dirs = ["/".join(parts[:i]) for i in range(len(parts), -1, -1)]
        if not any((d + "/" if d else "") + lock in have for d in dirs for lock in LOCKFILES[name]):
            report("error", "lockfile", f"commit the lockfile ({' or '.join(LOCKFILES[name])}) so the archive "
                   "records the versions you measured with", f)
    released = [f for f in tracked if f.startswith(".github/workflows/")
                and re.search(r"\brelease\b", read(f)) and "ghcr.io" in read(f)]
    if not released:
        report("error", "release", "no workflow publishes the runtime image to ghcr.io on a release; "
               "copy .github/workflows/release.yml from the code template")


def check_secrets_and_ignores(tracked):
    for f in tracked:
        if re.search(SECRET_PATHS, f, re.I) and not f.endswith(".env.example"):
            report("error", "secret-file", "secret-like file is tracked; keep it in an untracked .env and commit "
                   "placeholders in .env.example", f)
    # A reason comment covers the contiguous block of entries directly below it, up to the next
    # blank line or comment; an entry whose block has no comment above it is an error.
    covered = False
    for n, line in enumerate(read(".gitleaksignore").splitlines(), 1):
        line = line.strip()
        if not line:
            covered = False
        elif line.startswith("#"):
            covered = True
        elif not re.fullmatch(FINGERPRINT, line):
            report("error", "gitleaksignore", "not an exact gitleaks fingerprint (commit:path:rule:line)", ".gitleaksignore", n)
        elif not covered:
            report("error", "gitleaksignore", "put a one-line reason as a comment directly above this entry's block", ".gitleaksignore", n)


def publish_checks(hist):
    tracked = git("ls-files").splitlines()
    if not Path("PUBLISHING.md").is_file():
        report("error", "checklist", "PUBLISHING.md is missing; copy scripts/publishing-checklist.md there and fill it in")
    elif "PUBLISHING.md" not in {l.split(": ")[0] for l in git("check-attr", "export-ignore", "--", "PUBLISHING.md").splitlines()
                                 if l.endswith(": set")}:
        report("error", "checklist", "mark PUBLISHING.md export-ignore in .gitattributes", "PUBLISHING.md")
    check_files(tracked)
    check_citation(check_zenodo())
    check_readme()
    check_docker_release(tracked)
    check_secrets_and_ignores(tracked)
    history_grep("paper-source", PAPER_SOURCE, "paper source", hist, exclude=STANDALONE)
    history_grep("local-path", LOCAL_PATHS, "machine-specific path", hist)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--strict", action="store_true", help="fail on history findings too (optional local check)")
    ap.add_argument("--publish", action="store_true", help="the publication gate: also check what the Zenodo "
                    "release needs; CI runs it once PUBLISHING.md exists")
    a = ap.parse_args()
    hist = "error" if a.strict else "warning"

    tree_grep("private-link", PRIVATE_LINKS,
              "link to a private or unpublished place; public repositories link public URLs only")
    tree_grep("local-path", LOCAL_PATHS, "machine-specific path; use a relative path or a variable")
    tree_grep("paper-source", PAPER_SOURCE, "paper source; it belongs in the paper repository", "*.tex", STANDALONE)
    history_grep("private-link", PRIVATE_LINKS, "link to a private or unpublished place", hist)

    seen = set()
    for ident in git("log", "--all", "--format=%an <%ae>%n%cn <%ce>").splitlines():
        if ident not in seen and re.search(AGENT_IDENTITY, ident, re.I):
            seen.add(ident)
            report(hist, "agent-author", f"commits authored or committed as {ident}; commit as yourself "
                   "(a Co-Authored-By trailer is fine), then rewrite these commits before going public")

    archived = git("ls-files").splitlines()
    ignored = git("check-attr", "export-ignore", "--", *archived) if archived else ""
    excluded = {l.split(": ")[0] for l in ignored.splitlines() if l.endswith(": set")}
    for path in archived:
        if re.search(AGENT_FILES, path) and path not in excluded:
            report("error", "agent-file", "agent file would be archived; add it to .gitattributes "
                   "as export-ignore or remove it", path)

    if a.publish:
        publish_checks(hist)

    print(f"hygiene{' --publish' if a.publish else ''}: {findings['error']} error(s), {findings['warning']} warning(s)")
    sys.exit(1 if findings["error"] else 0)


if __name__ == "__main__":
    main()
