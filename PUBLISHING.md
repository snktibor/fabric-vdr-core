# Publishing checklist

Copy this file to `PUBLISHING.md` at the repository root when you start preparing the Zenodo
release. From then on, the `hygiene` workflow runs the publication gate (`just hygiene --publish`),
which checks everything a script can check. The items below are the rest. Tick each one and
write the evidence after it: a commit, a file and line, a command and its output, or a link. An
advisor signs the list off by approving the pull request that completes it. `.gitattributes`
keeps this file out of the Zenodo archive.

Filled in on 2026-11-01 on the `publish/zenodo-gate` branch, on `main` at `54f0a11`, by Claude Opus 5.5 for Martin. An unticked box
is open: its evidence says what is missing.

## Results

- [ ] `just reproduce` regenerates every figure and table in the paper, and `just reproduce --smoke`
      finishes in minutes. Evidence:
      - `justfile:110`: `reproduce *flags: check e2e (benchmark flags)`, and `justfile:107`:
        `check: test formal results figures`.
      - `just figures` (`justfile:84`) regenerates the six CSVs behind the scalability and robustness
        figures. On the committed runs, `diff -r` against the paper's `figures/data/` is empty
        (run 2026-10-31, and again on 2026-10-31 inside the reproduce image as a non-root user).
      - The parts have run, but not as one `just reproduce --smoke` command:
        - In CI on `cleanup/zenodo` (2026-10-31, before the history rewrite), the reproduce image
          ran `just check` in under 1 min, after a 3 min build.
        - The `live-network` job ran `just e2e` (about 12 min) and `just benchmark --smoke` (about
          4 min).
      - Open: nobody has run `just reproduce --smoke` end to end on one machine. Its expected time is
        about 20 min, the sum of the parts above.
- [ ] Recipes are named after their outputs, such as `fig-scalability` or `tab-robustness`. Evidence:
      - Open, for Martin to decide. The recipes are named after what they check or measure:
        `test`, `formal`, `e2e`, `benchmark`, `robustness`, `figures` and `results`
        (`justfile:16`–`110`). The README's results map (`README.md:87`) gives the recipe for each
        figure and table.
      - Renaming would break the commands that the paper (`05-evaluation.tex`, "Artifact
        availability") and CI already use, so I left the names.
- [x] The results map has one row per figure and table of the paper, each with its command,
      output path and time on the stated hardware. Evidence:
      - `README.md:87` (Results map). It has rows for:
        - the evaluation chapter: `tab:correctness`, `fig:results-b1u-b1`, `fig:scalability`,
          `tab:robustness` with `fig:robustness`, and `tab:eval-target`;
        - the appendix's Lean results: `fig:lean-proof-tree`, `tab:theorems` and `tab:mutants`.
      - I listed every label with
        `grep -o '\\label{\(fig\|tab\):[^}]*}' content/*.tex` in the paper repository. The other 15
        are diagrams or comparisons with nothing to rerun:
        - `fig:overview`, `fig:execute-order-validate`, `fig:self-orchestration`,
          `fig:lean-trusted-kernel`, `fig:positioning`, `fig:race-two-panels`, `fig:uml-component`,
          `fig:uml-seq-guarded-update`, `fig:uml-deployment`;
        - `tab:platform-choice`, `tab:related`, `tab:model-fabric`, `tab:attacker`,
          `tab:security-aspects`, `tab:related-vdr`.
      - The README names rows by caption, not by number, because no compiled PDF was available to
        read the numbers from.
- [ ] Every dependency is pinned in the `Dockerfile` and the lockfiles. Evidence:
      - Pinned:
        - the base image `maven:3.9.16-eclipse-temurin-17` (`reproduce.Dockerfile:15`), by tag,
          not by digest;
        - elan `v4.2.4` (`reproduce.Dockerfile:28`), and the Lean toolchain through fabric-vdr-formal's
          `lean-toolchain`;
        - fabric-vdr-formal at commit `ae57a65` (`justfile:7`);
        - Fabric `2.5.15` (`bench/fablo/1org-solo.json:4`, `bench/fablo/3org-raft.json:4`) and
          Fablo `2.6.0` (CI and `.devcontainer/devcontainer.json`);
        - the Maven dependency versions in `pom.xml`.
      - Open:
        - the apt packages in `reproduce.Dockerfile:20` (`just`, `patch`, `bsdextrautils`,
          `python3`) take whatever Ubuntu 24.04 ships;
        - `ghcr.io/gitleaks/gitleaks:latest` in the hygiene workflow is unpinned, as it is in the
          template;
        - Maven has no lockfile; `pom.xml` pins direct versions only.

## Metadata

- [x] The license was agreed with the advisors: Apache 2.0 for a tool, MIT otherwise. Evidence:
      - `LICENSE` is Apache-2.0, and so are `CITATION.cff` (`license: Apache-2.0`) and
        `.zenodo.json` (`"license": "Apache-2.0"`).
      - Martin's ruling of 2026-10-31 (code cleanup brief) is Apache 2.0 for tools, and this
        repository is a tool: chaincode with a method specification in `docs/anoncreds-method.md`.
- [ ] `related_identifiers` in `.zenodo.json` lists `isDerivedFrom` for a fork's upstream and
      `isSupplementTo` for each paper with a DOI, and nothing else. Evidence:
      - Open: no `isSupplementTo` yet. The paper has no public identifier: its repository links
        only Overleaf and the private `BlackLight54/TDK26-Sinka-Tibor`. Following guide 08:14,
        nothing goes in until the paper has a DOI or a public URL.
      - Conflict, for Martin: `.zenodo.json` has two other entries, which "nothing else" excludes:
        - `requires` `https://github.com/snktibor/fabric-vdr-formal` (`just formal` fetches it);
        - `references` `https://github.com/snktibor/anoncreds-vdr-demo`.
      - Martin decided on 2026-10-31 to link the other two repositories this way. I kept them
        rather than override that decision.
- [x] A fork keeps the upstream license, names the upstream authors in `.zenodo.json` and says
      in the README what it changed. Write "not a fork" otherwise. Evidence: not a fork.
      `gh api repos/snktibor/fabric-vdr-core -q .fork` returns `false`. The repository started as
      Tibor's own chaincode; its first commits are by `snktibor` and `sinti`, which is Tibor's WSL
      user.
- [x] A tool has the tool README sections, `MAINTAINERS.md` and `SECURITY.md`. Write "results
      only" otherwise. Evidence:
      - It is a tool.
      - The README sections are:
        - Prerequisites (`README.md:168`), Build (`README.md:179`) and Unit tests
          (`README.md:196`);
        - End-to-end tests against a live network (`README.md:208`);
        - the Transaction reference (`README.md:333`), which is the use on one's own input.
      - Contributing is `CONTRIBUTING.md`, with DCO sign-off.
      - `MAINTAINERS.md` and `SECURITY.md` are present at the root.

## Before going public

- [ ] Every history warning of `just hygiene --publish` has been read. The history is not rewritten:
      each warning is either harmless once public or listed here with why it is acceptable. Evidence:
      - `local-path` in `.github/hooks/pre-commit`, commit `7beb10b` (2026-07-12, on `main`). The
        commit removes two lines that `cd` into a project directory under the home directory of the
        WSL user `sinti`. They reveal only that user name and a directory name. Harmless.
      - `local-path` in `src/test/java/com/example/vdr/e2e/DidSteps.java`, commit `9c8d85c`
        (2026-05-12, on `main`). The commit removes a hard-coded path to the crypto material of
        a local Fablo test network, under the same user's home directory. Harmless.
      - These two are the only history warnings, from a run in a fresh clone of `main`. The rewrite
        of `main` on 2026-11-01 removed every `Co-Authored-By` trailer, and the gate finds no
        agent identity.
      - `layout`: no `tests/` and no `experiments/`. The tests are in `src/test/` (Maven layout),
        and the measurements in `bench/` and `results/`. This is the repository's own working
        layout, which the brief says to keep.
      - `smoke`: no workflow runs `just reproduce --smoke`. CI runs its parts in separate jobs
        (`.github/workflows/ci.yml`):
        - `reproduce-image` runs `just check`;
        - `live-network` runs `just e2e` and `just benchmark --smoke`.

        `just reproduce --smoke` in one job would also need elan and Lean on the live-network
        runner.
      - `devcontainer`: `.devcontainer/devcontainer.json` uses a Microsoft Java image, not
        `reproduce.Dockerfile`. The reproduce image has neither Docker nor Fablo, which the E2E
        suite needs on the host.
      - `readme`: no concept DOI badge yet. That is expected before the first release; the badge
        is the student's step after it.
      - Open: Martin reads the history warnings of the CI run on the PR. The PR body lists them.
- [ ] Every secret gitleaks ever found has been rotated: the key, token or wallet no longer
      works or holds anything. Evidence:
      - The hygiene workflow's gitleaks step reports no unaccepted finding on any published branch
        (CI run on this PR).
      - gitleaks on a fresh clone of `main` alone (`54f0a11`, 106 commits) reports 0 findings.
      - `.gitleaksignore` on `main` accepts:
        - 3 public keys in `docs/anoncreds-method.md` (commit `d1303cb`), which are published on
          the ledger by design;
        - 5 SHA-256 digests in `verification/lean/toolchain-lock.json` (commit `a293bf7`).
      - It also keeps 34 entries for commit `db8f802`: 18 digests, plus 16 hits for 8 expired E2E
        DID-JWTs.
        - That commit is not in `main`'s rewritten history.
        - `git log -G 'eyJ...\.eyJ' --all` on the fresh clone finds no JWT in `main`'s history.
        - The commit stays reachable on GitHub through the closed PRs
          ([#4](https://github.com/snktibor/fabric-vdr-core/pull/4) and the PRs on top of it) and
          the `publish/gate` branch.
      - Open, for Martin to decide (#4): whether to accept the 16 JWT hits, and whether to keep
        the stale `db8f802` entries.
        - The other session's audit (`verification/PUBLICATION-AUDIT.md` on #4) found no private
          key committed. It could not verify the signatures, because the recorded blocks lack the
          issuer public keys.
        - If the JWTs count as leaked, rotation is moot: they expired on 2026-10-25, and their test
          network no longer exists.
- [x] No link points to a same-year TDK paper. Evidence: every link in the tree points to one of:
      - this repository or Tibor's public `fabric-vdr-formal` and `anoncreds-vdr-demo`;
      - upstream projects: Hyperledger, Fablo, Lean/elan, KeY, just, Docker;
      - licences and policies: Apache, Contributor Covenant, the DCO;
      - eprint.iacr.org 2020/777;
      - the Zenodo badge placeholder;
      - `example.com` placeholders in the API examples.

      I listed the hosts with `git grep -h -o -E 'https?://[^ )]+' -- . ':!results'` on this branch.
      The gate's private-link check (`hygiene.py`, `PRIVATE_LINKS`) is clean on the tree.
- [ ] No AI app or bot is a collaborator or installed app with write access (Settings,
      Collaborators and GitHub Apps). Evidence:
      - Collaborators: `gh api repos/snktibor/fabric-vdr-core/collaborators` lists two users,
        `snktibor` (admin) and `BlackLight54` (write), and no bot.
      - Open: installed GitHub Apps need the owner's view. Tibor checks
        <https://github.com/snktibor/fabric-vdr-core/settings/installations>.
- [x] A `CLAUDE.md` or `AGENTS.md` that documents the code was renamed, so the archive keeps it.
      Evidence: there is none. `git ls-files | grep -i -E 'claude|agents\.md|gemini|cursor|codex'`
      prints nothing, and `.gitattributes` keeps any future agent file out of the archive.

## Release (the student's steps, after the pull request merges)

- [ ] The repository and its ghcr.io package are public, and an anonymous
      `docker manifest inspect ghcr.io/snktibor/fabric-vdr-reproduce:v1.0.0` succeeds. Evidence:
      - The repository is already public (`gh api repos/snktibor/fabric-vdr-core -q .visibility`
        returns `public`).
      - The package `fabric-vdr-reproduce` appears with the first release
        (`.github/workflows/release.yml`).
- [ ] Zenodo is enabled for the repository, and release `v1.0.0` is tagged on the commit whose
      results match the paper. Evidence:
- [ ] The Zenodo record's files and metadata are checked, including both supervisors. The
      concept DOI badge in the README resolves. Evidence:
