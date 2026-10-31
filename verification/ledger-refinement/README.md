# Concrete ledger refinement

This directory refines the [base protocol model](../lean/) to a concrete ledger that is closer
to the chaincode: optional records with signed-long versions, creation at version 0, plain and
guarded updates, revision ranks separate from application versions, writes to other keys such
as nonces, and arbitrary state-preserving rejection. The refinement keeps the application
state, every commit occurrence and the persistent revoked history, so the asynchronous
no-bifurcation theorem carries over: `LedgerRefinement.Atomic.no_bifurcation` is the result
that faces the implementation.

It is a theorem about a semantic model of the ledger. It does not verify the Java code or
Fabric; [CONTRACT.md](CONTRACT.md) lists what remains for them.

## What is proved

The first model has eleven targets: ten are proved and one, the first `EndorsementSpec`, is
refuted and stated again correctly ([CORRECTION.md](CORRECTION.md)). The atomic extension
([ATOMIC-CONTRACT.md](ATOMIC-CONTRACT.md)) adds eight more.

- Records exist or are absent and carry signed versions; the invariant keeps existing versions
  in [0, 2^63-1], and negative or exhausted versions reject. `arithmetic` proves the natural
  successor and the bound, and `Atomic.committed_update_exact` proves that a committed target
  gets exactly its previous version plus one.
- Creation at 0 is part of the same run. Revision stability and snapshot provenance show that
  a committed creation sees an absent target, so it cannot reset an existing key. Every plain
  and guarded writer is represented, and there is no delete or reset.
- Endorsement checks target and guard existence, the actual guard versions and that the
  successor fits. Target, guard and other-key revisions must all match at validation; equal
  application versions never replace equal revisions.
- Entry revisions and application versions are distinct, and "same revision, same record" is
  proved from the transitions rather than assumed.
- A target write and its nonce or other-key writes apply at one occurrence, each key once. A
  failed endorsement, an MVCC conflict or an external rejection keeps the whole state.
- State, commit and revoked-history simulation hold for both the first model and the atomic
  extension. The `allow` filter is computed from the concrete run, and its allowed occurrences
  are proved to pass the abstract reads and make exactly the projected change.
- No-bifurcation transfers with an arbitrary well-formed initial state and initially revoked
  history, independently timed checks, different endorsement snapshots and arbitrary
  interleavings, without a common fresh check.

New theorems depend on `propext` and `Quot.sound` only. The base model's optional
`rejected_has_prior_committed_writer` still uses `Classical.choice`; the safety chain does not.

## A worked race

In `Witnesses.race`, registry keys 0, 1 and 2 start at version 0 with accumulator payload 42,
and their entry revisions are 100, 101 and 102, so revisions and versions are visibly
different. At occurrence 0 an unrelated plain writer updates registry 2 to version 1 and
payload 53, and changes the revision of nonce key 10 in the same occurrence. Honest A checks
B's registry at prefix 0, honest B checks A's registry at prefix 1.
`check_registry_presence_and_provenance` shows that both checked registries exist with the
stated version and accumulator at those prefixes and in the endorsement snapshots, and
`honest_A` and `honest_B_later_check` give the pinned guards and the unrevoked history. A
commits at occurrence 1, and B's stale reciprocal transaction is rejected at occurrence 2.

The payloads 42, 51, 52 and 53 are opaque values, not computed accumulators. To relate such
checks to a real client, the client has to check the accumulator of the existing registry and
pin the version read from that same record; a version obtained separately from the checked
accumulator is not covered. `extra_conflict_despite_matching_application_reads` shows the nonce
write at occurrence 0 invalidating a later transaction whose target and guard versions still
match, and `creation_zero_then_plain_one` updates a newly created key from a later snapshot.

## Running it

Set up the toolchain in `../lean` first (see its README), then run from any directory:

```sh
python3 verification/ledger-refinement/scripts/replay.py
```

The script reuses the pinned Lean 4.23.0 from `../lean/tools` and writes only inside this
directory. It checks the sealed inputs in `frozen/` and the toolchain hashes, compiles every
module into a new directory with `--trust=0`, checks the exact target types, audits the axioms
of every theorem in both namespaces and writes `result.json`, `SUCCESS`, `THEOREMS.md`,
`theorem-inventory.json` and logs. A failed run removes the old success marker first.
`LEAN_MODEL_DIR` points at another copy of the base model and `LEAN_TOOLCHAIN_DIR` at another
installation of the same release.

With `--check-package`, the script also packs the sources into
`artifacts/ledger-refinement-source.tar.gz`, replays the package in a new directory, then
changes a sealed file in that copy and checks that the replay fails and removes its success
marker. The package holds sources, the base-model files it seals and the toolchain lock, but
no toolchain or compiled files.

## Platform assumptions

Fabric revisions are block and transaction positions. The model uses a per-key rank that
preserves their equality and is fresh on every write, other keys included, and it requires
endorsement snapshots to be earlier prefixes of the run. The rank view, atomic validation and
rollback, ordering and snapshot provenance have to hold for the deployed platform; they are
not proved from the Fabric source.
