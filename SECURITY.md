# Security Policy

## Reporting a vulnerability

**Please do not report security vulnerabilities through public GitHub issues,
discussions, or pull requests.**

Instead, report them privately by e-mail to:

> **<sinka.tibor@edu.bme.hu>**

Please include as much of the following as you can:

- A description of the vulnerability and its impact (e.g. unauthorized state
  mutation, authentication bypass, denial of service of the chaincode or peers).
- Steps to reproduce, ideally as a failing test or a transaction sequence against
  the development network from [`anoncreds-vdr-demo`](https://github.com/snktibor/anoncreds-vdr-demo).
- Affected version / commit hash.

## What to expect

- We will acknowledge your report within **7 days**.
- We will investigate, keep you informed of progress, and agree on a coordinated
  disclosure date with you — normally within **90 days** of the report.
- Once a fix is released, we will credit you in the release notes unless you
  prefer to remain anonymous.

## Scope notes

This project is a prototype-stage Verifiable Data Registry. In particular, the
following are *known, documented* limitations rather than reportable
vulnerabilities (see the [README](README.md)):

- `type` strings and the `accumValue` / `publicKeys` payloads are not validated
  or size-bounded by the chaincode.
- Read transactions (`get*`, `list*`) are unauthenticated by design — the
  registry stores public data.
- DID deactivation is irreversible and freezes the issuer's registries.
