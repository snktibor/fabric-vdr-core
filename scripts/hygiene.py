#!/usr/bin/env python3
"""Publication hygiene: what must not reach a public repository or its Zenodo archive.

Run by `just hygiene` and by the `hygiene` CI workflow on every push. Checks of the
current tree fail the run. Checks of the history only warn, because history needs a
rewrite to fix; `--strict` makes them fail too, and is the check to pass before the
repository goes public or gets its first Zenodo release (guide: "Before the repository
goes public").

Secrets are gitleaks' job (see .github/workflows/hygiene.yml); this script covers the rest.
"""
import argparse, os, re, subprocess, sys

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

findings = {"error": 0, "warning": 0}


def git(*args):
    r = subprocess.run(["git", *args], capture_output=True, text=True)
    if r.returncode not in (0, 1):  # 1: git grep found nothing
        sys.exit(f"hygiene: git {' '.join(args)} failed: {r.stderr.strip()}")
    return r.stdout


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


def history_grep(rule, rx, msg, level):
    # Commits whose diff adds or removes a matching line, on every branch.
    out = git("log", "--all", "-i", "-E", f"-G{rx}", "--format=@%h", "--name-only",
              "--", ".", f":!{SELF}")
    commit, seen = None, set()
    for l in out.splitlines():
        if l.startswith("@"):
            commit = l[1:]
        elif l and l not in seen:  # newest commit per path is enough to find it
            seen.add(l)
            report(level, rule, f"{msg} (history, commit {commit})", l)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--strict", action="store_true", help="fail on history findings too")
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

    print(f"hygiene: {findings['error']} error(s), {findings['warning']} warning(s)")
    sys.exit(1 if findings["error"] else 0)


if __name__ == "__main__":
    main()
