# Lean model of guarded revocation

This directory is a second Lean model of the guarded-update protocol, written from the
paper's definitions and kept separate from [argos-formal](https://github.com/snktibor/argos-formal),
which the paper cites. The two models are not proved equivalent. This one is the base that
the [ledger refinement](../ledger-refinement/) and the
[service-trace bridge](../service-trace-bridge/) build on.

It proves 14 target statements:

- seven for the base protocol: monotone versions, strict growth on a committed write, stale
  reads never commit, test-and-set, the closed check/use window, the checked fact still
  holding at use, and reciprocal safety (`no_bifurcation`);
- two separate results: a rejected transaction has an earlier committed writer, and an
  initially fresh pair has at most one winner;
- five for rejections other than MVCC, proved through a no-op simulation (`skip_*`).

`no_bifurcation` holds for any stream, independently timed checks, independently endorsed
calls and any interleaving. Its commit claims refer to occurrence indices. It does not assume
a common check state or initially fresh versions. `check_holds_at_use` is about the state just
before validation, not after the transaction's own write.

No Java or Fabric code is verified here. How the model corresponds to the implementation,
whether accumulator updates are faithful, and the cryptography are outside its scope.

## Files

- [CONTRACT.md](CONTRACT.md): scope, encoding and the target statements;
  [Model.lean](Model.lean): definitions and exact propositions;
  [Proofs.lean](Proofs.lean) and [Branches.lean](Branches.lean): proofs.
- [SKIP-CONTRACT.md](SKIP-CONTRACT.md), [SkipModel.lean](SkipModel.lean) and
  [SkipProofs.lean](SkipProofs.lean): the extension for non-MVCC rejection.
- [Examples.lean](Examples.lean) and [SkipExamples.lean](SkipExamples.lean): concrete
  executions and counterexamples.
- [Audit.lean](Audit.lean): checks each target's exact type and prints the axioms of every
  theorem in the model, including generated ones.

The examples cover different check prefixes, unrelated writes between protocol events,
either party winning, two unguarded revocations both committing, and two honest guarded
revocations both failing. `ce_future_pin_both_commit` shows why the fresh-pair theorem needs
both pins to be no newer than the initial state: with freshness for the first transaction
only, both commit.

## Running it

You need Linux x86_64, Bash, Python 3, curl, tar and zstd. The pinned toolchain is installed
under `tools/` in this directory; nothing is installed globally and Lake downloads nothing.

```sh
bash scripts/setup-toolchain.sh
bash scripts/verify.sh
python3 scripts/test-failure-invalidation.py
```

`verify.sh` deletes any earlier success marker first. It then checks the sealed hashes in
`frozen/` and the pinned toolchain files, rejects `sorry`, `admit`, `native_decide` and custom
axioms, and compiles every module into a new build directory. A failed compile, a missing
target or an axiom outside the whitelist fails the run. `result.json` holds the verdict, and
`SUCCESS`, `THEOREMS.md` and `theorem-inventory.json` exist only after a complete run.
`test-failure-invalidation.py` edits a sealed file in a copy and checks that the replay fails
and removes the old success marker.

The whitelist is `propext` and `Quot.sound`. Only `rejected_has_prior_committed_writer` also
uses `Classical.choice`; the safety chain and the skip simulation do not.

`toolchain-lock.json` records the release URL, the archive digest and the hashes of the
executables and of 1,854 runtime and library files. The digest pins the HTTPS download; it is
not a checksum published by the Lean project.

## Scope

The total key-to-natural state covers registry keys that already exist, and every committed
write strictly increases its key. Creating a registry at version 0 is not a transition of this
model, and unbounded natural versions do not model Java's signed `long`. The ledger refinement
adds both. `RevWF` and the persistent revoked history are definitions, not evidence that the
accumulator or the issuer behaves faithfully.
