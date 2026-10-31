# Extension: atomic writes to other keys

A real registry operation can write a nonce key in the same transaction as the registry entry.
The first ledger model could write other keys only in a separate `Mode.extra` occurrence, so it
cannot represent that trace directly. This extension adds it. The first model and its target
statements are unchanged; `frozen/atomic.sha256` seals this file, `AtomicModel.lean` and
`AtomicSpec.lean`.

`AtomicModel.lean` gives every transaction, in every mode, a finite `extraWrites` list. A
successful occurrence applies its registry creation or update, or its primary other-key
operation, and increments each listed key's revision exactly once at the same occurrence. If
the primary other key is also listed, it still increments once. Entries, entry revisions and
other-key revisions change together, and a rejected occurrence changes none of them. Other-key
reads keep exact revision-equality validation, so a registry transaction that writes a nonce
invalidates a later stale reader of that nonce at the right occurrence.

Other keys have opaque values; only their MVCC revisions are modelled. Checks on nonce content,
authentication and policy are covered by the arbitrary permission filter, whose accepted
executions need justification from the implementation. The platform rank assumption, snapshot
provenance and the Java, JVM, Fabric, authentication and accumulator assumptions of
[CONTRACT.md](CONTRACT.md) still apply. No nonce-policy theorem follows from this extension.

`AtomicSpec.lean` states eight targets: the atomic write footprint, the bounded-state
invariant, no reset by creation, state simulation, commit simulation, revoked-history
simulation, exact state-preserving rejection, and the asynchronous no-bifurcation transfer.
Every registry writer still occurs; other-key writes never excuse dropping one. Initial states,
checks, interleavings, read and write sets and rejections are arbitrary within the invariants,
and snapshot provenance refers to this atomic run.

The witnesses show a target update and a nonce revision change at one occurrence, a later
MVCC conflict on another key while the target and guard versions still match, and an honest
reciprocal race with distinct check times.
