# Contributing to fabric-vdr-core

Thank you for your interest in contributing! This document describes how to build and
test the project and what we expect from pull requests.

## Developer Certificate of Origin (DCO)

This project enforces the [Developer Certificate of Origin](https://developercertificate.org/)
(DCO), the same contribution policy used by Linux Foundation projects. Every commit
must carry a `Signed-off-by` trailer asserting that you have the right to submit the
change under the project's [Apache-2.0 license](LICENSE):

```bash
git commit -s -m "feat: describe your change"
```

which appends:

```
Signed-off-by: Your Name <your.email@example.org>
```

Use your real name and a reachable e-mail address. Pull requests containing commits
without a sign-off will not be merged; use `git rebase --signoff` to fix up an existing
branch.

## Building and testing

Prerequisites: JDK 17, Maven 3.9+, and Docker (for the end-to-end suite only). See the
[README](README.md) for details.

```bash
mvn clean package        # build the shaded chaincode jar
mvn test                 # unit tests (no network needed)
mvn spotless:apply       # format (google-java-format, AOSP style)
```

For the end-to-end suite, bring up the companion
[`anoncreds-vdr-demo`](README.md#end-to-end-tests-against-a-live-network) and run:

```bash
mvn -Pe2e verify
```

## Pull request expectations

- **One logical change per PR.** Keep refactoring, formatting and functional changes in
  separate commits (ideally separate PRs).
- **Formatting must be clean**: `mvn spotless:check` passes (CI enforces this).
- **Tests must be green**: `mvn test` passes; if your change affects behaviour that the
  E2E suite covers, run `mvn -Pe2e verify` against a freshly bootstrapped network and
  say so in the PR description.
- **New behaviour needs tests.** Service-layer changes need unit tests; new or changed
  transaction semantics need a Cucumber scenario.
- **Determinism is non-negotiable.** Chaincode executes on multiple peers and results
  are compared byte-for-byte: no wall-clock time (use the transaction timestamp), no
  randomness, no iteration over unordered collections when building responses, and all
  emitted JSON must go through the canonical serializer (`JsonUtil`).
- **No credential or personal data on the ledger.** The registry stores public
  parameters only; changes that would persist holder-correlatable data will be
  rejected.
- Write Javadoc in the same style as the surrounding code (every public method is
  documented).

## Reporting issues

Use the issue tracker for bugs and feature discussions. For suspected security
vulnerabilities, **do not open a public issue** — follow [SECURITY.md](SECURITY.md).
