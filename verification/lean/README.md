# Guarded revocation: independent Lean reconstruction

Publication note (2026-10-31): the original model is now public in [argos-formal](https://github.com/snktibor/argos-formal/tree/ae57a657d58a401d433a9475e4b5fc91e71baf37). Availability statements in the frozen contract and historical review describe the reconstruction date; they do not describe current access. No equivalence proof to the original source is claimed.

This project newly proves an abstract version/transaction protocol from the supplied paper. It is independent of the original `argos/lean` development, which was unavailable when reconstructed. No Java or Fabric implementation is verified here. Concrete-to-model correspondence, faithful accumulator updates, cryptography and original-source comparison remain OPEN.

The exact definitions and fourteen named contract propositions were frozen before their proof searches. Seven core contracts cover monotonicity, strict committed growth, stale reads, test-and-set, the closed check/use window, the checked fact at use, and reciprocal protocol safety. Two separate branches cover prior-writer detectability and initially fresh pairs. Five extension contracts cover arbitrary additional state-preserving rejection through a proved concrete no-op simulation.

`no_bifurcation` quantifies over arbitrary streams, independently timed checks, independently endorsed calls and arbitrary interleavings. Its commitment claims refer to occurrence indices. It does not assume a common check state or initially fresh versions. `check_holds_at_use` concerns the state immediately before validation, not after the transaction's own write.

## Replay

Requirements: Linux x86_64, Bash, Python 3, curl, tar and zstd. The pinned toolchain stays inside this directory. There is no global Lean installation or package download through Lake.

```sh
bash scripts/setup-toolchain.sh
bash scripts/verify.sh
python3 scripts/test-failure-invalidation.py
python3 scripts/package-source.py
python3 scripts/test-portability.py
```

`verify.sh` invalidates any prior success before loading Python. Each run checks the frozen hashes and pinned executable/runtime/library hashes, rejects forbidden proof mechanisms, and compiles all modules into a new build directory. `Audit.lean` checks each contract's exact type and prints the transitive axioms of every theorem constant in the reconstruction, including witnesses, the skip extension and generated helpers. Compilation failure, a missing target or an unapproved axiom makes the run fail and removes previous theorem-inventory success artifacts. `result.json` is the current machine-readable verdict; `SUCCESS` exists only after a complete successful run.

The baseline whitelist is `propext` and `Quot.sound`. Only `rejected_has_prior_committed_writer` additionally uses `Classical.choice`, for classical existence extraction from failed universal validation. The protocol safety chain and skip simulation do not use it. There are no placeholders, custom axioms or native decision procedures. Ordinary `decide` in closed examples produces kernel-checked proof terms.

`toolchain-lock.json` records the official release URL, the pinned downloaded archive digest, executable hashes and 1,854 runtime/library hashes. The digest is a local pin of the HTTPS release download, not a claim of an independently signed publisher checksum. The archive and installed files live in ignored `tools/`.

## Read the artifacts

- [Frozen baseline contract](CONTRACT.md), [definitions and exact propositions](Model.lean), [proofs](Proofs.lean), [separate branches](Branches.lean).
- [Frozen skip contract](SKIP-CONTRACT.md), [skip definitions](SkipModel.lean), [simulation and safety proofs](SkipProofs.lean).
- [Concrete honest executions and counterexamples](Examples.lean), [nontrivial skip execution](SkipExamples.lean).
- [Current result](result.json), [checked theorem inventory](THEOREMS.md), [full machine-readable inventory](theorem-inventory.json), [axiom command](Audit.lean).
- [Paper coverage and corrections](PAPER-COVERAGE.md), [semantic review](REVIEW.md), [source provenance](provenance.json), [contract-attribution correction](frozen/provenance-correction.json).

The examples include different check prefixes, unrelated committed writes between protocol events, either party winning, unguarded revocations both committing, and honest guarded revocations both failing. The future-pin example refutes the paper's pair-alone prose if it is read as assuming only the first transaction's freshness. It does not refute the frozen fresh-pair theorem. The skip example proves that rejecting an earlier writer can enable a later commit, so its argument is not a subset-of-commits claim.

## Scope and portability

The total key-to-natural state covers already-existing registry keys. Every committed model write strictly increases its key. Creating an absent registry at version 0 is outside this transition system. Unbounded natural versions do not model Java signed-long overflow. The explicit `RevWF` write-consistency condition and persistent revoked history are mathematical assumptions/definitions; they do not establish faithful accumulator or issuer behavior (paper AS7/AS8).

Copy the root `.lean` files, Markdown files, `lean-toolchain`, `toolchain-lock.json`, `provenance.json`, `.gitignore`, `scripts/`, `frozen/`, and desired small evidence logs. Paths used by the replay scripts are relative to the project location. Do not commit `tools/`, `build/`, root `.olean` files, or `SUCCESS`. All can be recreated locally. The generated inventory distinguishes named contracts, auxiliary lemmas, concrete witness/counterexample theorems and compiler-generated theorem constants. These counts do not reproduce the paper's stated twenty main theorems.

`package-source.py` checks that the successful replay still matches all source hashes, then creates `artifacts/lean-reconstruction-source.tar.gz` and a file/hash manifest. The archive includes source and small evidence logs, with no toolchain, compiled objects, build directories or success marker. A relocated copy must replay to create its own current success marker.

The portability check extracts that archive at a fresh path and replays it, reusing the same local toolchain by symlink. Its evidence is in `logs/portability.json`. This tests path independence without downloading another release copy.
