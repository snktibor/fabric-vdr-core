# Service-trace bridge

The bridge connects observations of the Java service calls to the atomic ledger model of
[../ledger-refinement](../ledger-refinement/). If every call that returns normally meets its
Java contract, and the codec, runtime, platform and fidelity premises hold, then two honest
parties that guard each other cannot both commit their revocations. The Java contracts are
premises here, not results; [JML-PREMISES.md](JML-PREMISES.md) lists them clause by clause.

It proves 12 targets, 18 auxiliary lemmas and 15 witness theorems.

- The commit interpreter checks only the normal return, the external permission and the
  captured read revisions, then installs the immutable write images and the atomic writes to
  other keys. It never evaluates `eligible`. The proofs derive eligibility from the observed
  records, the guard comparisons and the bounded target arithmetic, show that the written image
  equals `writtenRecord`, establish read coverage and revision equality, and prove that apply,
  step, run and commit agree with the atomic model. Every occurrence keeps its index, including
  rejections and creations, and a creation cannot overwrite a present target.
- The honest-check part carries a present record, with its accumulator and version at the
  party's check prefix, and the guard pins from that same prefix. The two parties keep
  independent prefixes. State, commit and revoked-history correspondence feed the asynchronous
  no-bifurcation theorem. `EndToEndSpec.realizes` is the intended effect of an authenticated
  payload; the conclusion rules out both transactions committing, it does not say that both
  revoke.
- The witnesses update a target read at version 3 with guards at versions 2 and 7 and write
  version 4, with a self-guard that still reports 3. Creation records an absent key and writes
  0. Inputs include a padded target and padded 96-character compressed-point hex payloads;
  these show the format, not curve or accumulator correctness. A nonce witness shows a conflict
  on another key rejecting a transaction whose entry reads still match. The witnesses are
  parameterized by the injective `Encoding` boundary; the bridge does not build or verify the
  production codec.

The main open obligation is a complete Java proof of `PerCallContracts`: the normal-case
`requires` clauses describe when a call succeeds, and they are not inverted into a
characterization of normal returns without the exceptional cases and the runtime domain.
Ordering, snapshot provenance, MVCC read registration, the rank view of revisions and rollback
of whole proposals are platform premises. Credential identities, check and pin provenance,
issuer behaviour and payload fidelity are client and cryptographic premises. The bridge does
not verify Fabric, JWT security or accumulator arithmetic.

## Files

- [CONTRACT.md](CONTRACT.md): the trace schema, premises and targets.
- [BridgeModel.lean](BridgeModel.lean): the immutable trace, the separate premises and the
  commit interpreter; [BridgeSpec.lean](BridgeSpec.lean): the targets;
  [BridgeProofs.lean](BridgeProofs.lean): their proofs;
  [BridgeWitnesses.lean](BridgeWitnesses.lean): the witnesses.
- [inputs/](inputs/): the JML contracts (`contracts-v4.json`), the clause catalog and the
  adapter stubs that the premises refer to.

## Running it

Set up the toolchain in `../lean` first (see its README), then:

```sh
python3 verification/service-trace-bridge/scripts/replay.py
```

The script uses the pinned Lean in `../lean/tools` unless `LEAN_TOOLCHAIN_DIR` says otherwise,
checks the toolchain lock of `../lean` and the seals in `frozen/`, and compiles the base model
files it needs from `../lean` and `../ledger-refinement` together with the bridge, each into a
new directory with `--trust=0`. It checks the 12 exact target types and audits the axioms of
every named and generated theorem. It writes `result.json`, `theorem-inventory.json`,
`logs/replay.log` and `logs/axioms.log`; a failed run replaces the result with a failure and
removes the inventory.

New theorems depend on `propext` and `Quot.sound` only. The base model's optional
`rejected_has_prior_committed_writer` keeps its `Classical.choice`; neither the bridge nor the
safety chain uses it.
