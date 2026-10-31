# Service-trace bridge: scope and target statements

The bridge connects what the Java service calls observe and write to the atomic ledger model
of [../ledger-refinement](../ledger-refinement/). It reuses that model and its proofs rather
than introducing another protocol. A normal return of a service call is interpreted through
named Hoare-style clauses that have to be checked outside Lean, so the result depends on that
cross-tool step and on the runtime; Lean neither runs nor verifies Java or Fabric.

`BridgeModel.lean` and `BridgeSpec.lean` define the trace schema and state the targets.
`frozen/semantic.sha256` seals them together with this file and the JML contracts in
`inputs/contracts-v4.json`, so a target cannot change without updating the seal. The bridge
imports the corrected endorsement statement of the ledger model and never treats the refuted
first version as proved.

## Requests

A normalized request has a mode, an occurrence and effect ID, the raw and normalized target
and accumulator, the signer, a timestamp, an ordered list of typed guards and a
guarded/plain flag. Java's `trim` removes characters with code unit at most 32 from both ends.
Lean's `String` covers only losslessly decoded Java UTF-16, so the runtime and codec have to
stay in that domain, or the representation has to be extended for unpaired surrogates.
Normalized IDs and payloads have injective encodings. Entry and other-key state are separate
namespaces, parent encoding is injective among other keys, and nonce keys are separate from
parent keys. Canonical Fabric composite keys, the actual JSON and guard parser outputs,
allowed key characters and valid compressed accumulator syntax are codec premises. No parser
or cryptographic check follows from the typed fields.

## Traces

A trace holds immutable entry observations (the requested key, an optional full record and
its captured revision), parent observations, authentication observations, immutable target
write images, other-key write keys, the returned checked versions and a normal-return bit. A
full record has the ID, accumulator, signed version and creation and update times, so mutating
a Java object after it was observed cannot change these values. `SnapshotRelation` ties the
answer at each consumed position to the proposal snapshot at that requested key and binds
every captured revision; it does not require any guard or counter to succeed. Well-formed
initial records and codec coherence distinguish absent bytes from JSON null, missing fields
and version 0. The stored ID is metadata, and writes go to the requested key.

For an update, the entry observations are the target followed by the non-self guards in
submission order, and the parent observation is the one normalized target. Guard `i` reuses
position 0 if it is a self-guard, otherwise position 1 plus the number of earlier non-self
guards. The checked output is that observation's old version and equals the expected version
in the request. The target version is nonnegative and below MAX; the written version is the
captured one plus one, the accumulator is replaced, the ID and creation time are kept and the
update time is the transaction's. Creation reads no entry, but its existence check becomes the
single entry observation, shown absent through the codec. Creation writes version 0 with the
normalized ID and the transaction time for both timestamps. A plain update has no guards, and
parents of non-self guards are not in the service's parent observations.

In the source, an update reads the target, reads and checks the parent, reads the guards, then
writes; a creation reads and checks the parent, checks existence, then writes. The per-registry
observation sequences do not prove the order across registries, and safety does not need it.

## Normal returns and commits

`NormalPost` lists every obligation as a separate field. No valid-transaction, eligibility,
simulation or abstract-correctness axiom is assumed. `PerCallContracts` says that every
occurrence that returns normally meets the named Java Hoare contract and the adapter contract.
This is not justified by reading the normal-case `requires` clauses as necessary conditions;
the public exceptional cases and the typed runtime domain have to establish that, or that part
stays unproved. [JML-PREMISES.md](JML-PREMISES.md) maps each field to its clauses. Rejected
occurrences need no postcondition: their permission or their normal-return bit is false. Every
occurrence keeps its index.

The commit interpreter checks only the captured entry and other-key revisions, the normal
return and the permission, then installs the immutable entry images and increments each
distinct other-key revision once. It does not evaluate `eligible`. The proofs show that it
equals `Atomic.step`, using the separate postconditions, the snapshot relation and the derived
read coverage and write equality. Occurrences that touch only other keys have no entry writes
and read and write their primary key; nonce and authentication writes happen atomically in
every mode. `AuthContract` records DID presence, nonce absence, the nonce write and namespace
separation, while the truth of the JWT, the owner and the policy are external. A failure after
the service returns, in an event, in serialization or in the caller, has to make the platform
permission false and discard every buffered write, nonce included. No in-memory rollback is
claimed.

## Platform and honest checks

The platform assumptions are a single ordered history, snapshots that are earlier prefixes of
it, revision ranks that preserve equality with no ABA on the modelled keys, and atomic
validation and rollback of whole proposals. The raw prefix equality and the transport of
`Origins` are proved; the behaviour of a deployed Fabric network and the rank correspondence
are premises. Every modelled entry writer creates at 0 or uses the checked successor; no
delete, reset or bypass writer is covered.

`HonestCheck` supplies a present record with both its accumulator and version at the party's
own check prefix, a successful client check against that record, and pins for every guard from
that same prefix. `CheckFidelity` reads a successful check as non-revocation in the persistent
history. `AuthenticatedFidelity` ties the intended effects to the authenticated payload effects
and supplies that check fidelity. The two checks need not share a prefix. Payload cryptography
and signer authenticity remain explicit premises, and an unchanged accumulator is never read
as a revocation.

## Target statements

`EligibilitySpec`, `WriteImageSpec`, `ReadCoverageSpec`, `CheckedProvenanceSpec`,
`ValidationSpec`, `ApplySpec`, `StepSpec`, `RunSpec`, `CommitSpec`, `NoResetSpec`,
`SafetySpec` and `EndToEndSpec`. The witnesses cover guards with different versions, a
self-guard that reuses the target value read before the write, creation, padded input and the
provenance of a present accumulator and version. They are parameterized by the injective codec
boundary. The replay compiles from scratch with `--trust=0`, checks the seals and the axioms of
every named theorem, and rejects custom axioms, `sorry` and `native_decide`.
