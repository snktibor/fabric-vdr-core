# Concrete ledger refinement: scope and target statements

`LedgerModel.lean` and `LedgerSpec.lean` define a concrete ledger and state the targets.
`frozen/semantic.sha256` seals them together with this file, and
`frozen/dependencies.sha256` seals the files of the [base model](../lean/) that they import.
Only proofs, witnesses and the audit can change without updating a seal.

## Scope

The concrete run has creation, plain updates, guarded updates and writes to other ledger
keys. Creation writes version 0 to an absent registry key. Both update forms advance a
nonnegative signed-long version by exactly one and reject negative or exhausted values. A
plain update is an update with no guards. Every registry writer is represented, including
unguarded writers to keys that honest parties guard. There is no delete, reset, migration or
out-of-band writer; a deployment with one must show that it fits these transitions or cannot
touch the modelled keys. Creation takes part in the safety transfer: an absent key projects
to 0, so creating it at 0 is a stutter step in the abstract state, and a theorem shows that a
committed creation never overwrites an existing record.

The initial state is arbitrary subject to `WellFormed`, which bounds every existing record's
version to [0, 2^63-1]. Absence, accumulator values, versions, entry revisions, other-key
revisions, initially revoked credentials and interleavings are otherwise arbitrary. Revisions
need not equal versions, and no pair is assumed fresh at the start. Negative versions can be
represented and are rejected at endorsement; that the invariant is preserved is a separate
target.

## Ledger and platform

`Ledger` has optional records and separate natural-number revision ranks. Every accepted write
advances the written key's rank, and a record write also replaces its value. Other keys have
their own namespace and ranks. In Fabric, a revision is a block and transaction position, not
an application counter and not a per-key successor. The platform assumption is that these
positions admit a per-key rank that preserves equality, is fresh on every write and has no
ABA. The rank increment is that representation. Fabric's ordering, MVCC check, atomic
rejection and this rank view are not proved from the Fabric source.

A proposal stores the endorsing peer's snapshot, the operation, the target and call, the new
accumulator and any other-key reads. `eligible` checks that the target exists (or is absent,
for creation), that every guarded record exists with exactly the expected nonnegative version,
and that the successor fits. A self-guard reads the same snapshot, and duplicate guards do no
harm. Successful endorsement records the target and guard revisions and every other-key read.
The model accepts more guard lists than the Java parser does.

`accepted` requires an external permission, successful endorsement and that every recorded
entry and other-key revision is unchanged at validation. Equal versions alone are not enough.
The permission stream is arbitrary and may depend on the state; it stands for any
authentication, policy, endorsement or validation failure. A false permission keeps the whole
state and never commits. A failure before submission is a rejected occurrence. The model
proves safety, not liveness or which error is reported.

`Origins` requires each proposal's snapshot to equal some actual prefix of the run no later
than the proposal's occurrence. Nothing is assumed about abstract endorsement, simulation or
validation. The proofs derive from the concrete transitions that the same revision means the
same record across prefixes, and use that to transfer reads and writes. That snapshots really
come from earlier prefixes of the run is a platform obligation. Checks and endorsements may
happen at different prefixes, and the two honest checks are independent.

## Projection, occurrences and revocation

The projection maps an existing signed version to a natural number and an absent key to 0.
Update transactions project to the base model's `endorsedTx` with the same target and guard
pins. Creation and other-key writes project to write-free transactions with the same ID. No
occurrence is removed or reordered. The abstract `allow` filter is computed from concrete
acceptance at each occurrence, so it keeps every concrete rejection reason, including stricter
MVCC checks and other-key conflicts. That every allowed occurrence passes the abstract reads
and makes exactly the projected abstract change is a theorem, not an assumption. Commit
equivalence is proved for every occurrence, creation and other-key writes included.

Credentials are `(registry key, credential index)` pairs, as in the base model. `Effects`
assigns intended revocations to operation IDs. A concrete revocation needs an update whose
target is the credential's registry and the assigned effect. The abstract revocation uses the
same effect and the written-key set. Creation and other-key writes revoke nothing. Repeated
IDs share their effect, so in a real deployment IDs must distinguish different intended
effects, for example by encoding the authenticated transaction identity. `RevWF` is proved
for this interpretation.

The ledger stores the accumulator as an opaque value. The fidelity assumption is that the
issuer's authenticated update realizes exactly the assigned revocations, that valid checks
agree with the persistent revoked history, and that credential and registry identifiers are
decoded correctly. Nothing here verifies accumulator cryptography, witnesses, key ownership,
proof of possession, issuer honesty, JWT verification, JSON canonicalization, timestamps,
exceptions, the compiler, the JVM or the network. Authentication and format failures can only
cause rejections.

## Target statements

The exact types are in `LedgerSpec.lean`: `ArithmeticSpec`, `EndorsementSpec`,
`CreationSpec`, `RevisionStabilitySpec`, `InvariantSpec`, `StateSimulationSpec`,
`CommitSimulationSpec`, `RevocationSimulationSpec`, `RejectSpec`, `NoResetSpec` and
`NoBifurcationSpec`. The last one transfers the base model's asynchronous skip theorem to the
concrete ledger, with independent check prefixes, an arbitrary initial state, arbitrary
interleavings and arbitrary rejection. It keeps distinct honest call IDs and each check before
its own use. `EndorsementSpec` as first written is false; see [CORRECTION.md](CORRECTION.md).

The witnesses cover successful creation, plain and guarded bounded updates, success at MAX-1
and rejection at MAX and below zero, missing and mismatching guards, a stale target revision,
an other-key conflict with matching guard versions, an external rejection without a state
change, a committed honest revocation, and a race with distinct checks and unrelated writes.

## What remains for the Java code

The model's semantics follow `createRevRegEntry`, `updateRevRegEntry`,
`updateRevRegEntryIfGuardsUnchanged`, `readGuardedVersion`, `checkGuardUnchanged`,
`requireOwnedEntry`, `applyAccumulatorUpdate`, `RevRegEntryVersion.next`,
`BaseRegistry.get/exists/put` and `RevRegEntryRecord`. Reading the code is not a refinement
proof. These obligations remain:

1. the namespace, key and credential identity mapping;
2. target existence, parent ownership and authentication, self-guard reuse and non-self guard
   reads;
3. comparing each guard against the value actually read;
4. the checked successor and the write order, with exactly one target `put`;
5. the absence check and version 0 on creation, and no writer that bypasses, resets or deletes;
6. every adapter read, including parent and authentication keys, being in the Fabric snapshot;
7. snapshot provenance and atomic rollback of every rejection and exception, serialization and
   object aliasing;
8. Fabric revision equality and ordered validation matching this rank model;
9. faithful accumulator interpretation and honest check pins;
10. Java, JVM, compiler and environment assumptions.

The [KeY proofs](../key/) cover the arithmetic of `RevRegEntryVersion.next` and the record
accessors; they do not discharge the obligations above.
