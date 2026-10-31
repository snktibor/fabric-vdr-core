# Java premises of the bridge

This table maps every Lean premise of the bridge to the JML clauses that would supply it.
The clauses are in [contracts-v4.json](inputs/contracts-v4.json), 37 cases over
`RevRegEntryService` and `AuthorizationService`, and [jml-clause-catalog.json](inputs/jml-clause-catalog.json)
gives the full text of each clause. Clause IDs use R for `requires`, E for `ensures`, A for
`assignable`, S for `signals` and O for `signals_only`, numbered within each case. The
[adapter stubs](inputs/) give the JML of the registry and validation calls that the contracts
rely on (D-READ, D-EXISTS, D-WRITE, D-JSON below).

The Lean proofs do not prove any of these clauses. `PerCallContracts` is a premise that a
Java verifier has to discharge with exactly these contracts, the same adapter environment and
a closed set of proof dependencies, and the result then has to carry over to the runtime. The
[KeY proofs](../key/) in this repository cover `RevRegEntryVersion` and `RevRegEntryRecord`
only; `RevRegEntryService` itself does not load in KeY 3.0.0 because of its generic types, so
the service cases below are not proved here.

| Lean field or obligation | Clauses that supply it | Remaining boundary |
|---|---|---|
| `TypedRequest.target_normalized`, `accumulator_normalized`, nonempty strings | S7.R01; S8.R01; S9.R01–R03 and validation adapter `idOutput`/`accumOutput` | D-JSON must bind outputs to actual `requireNotBlank`/`requireCompressedG1Hex` results, including trim. Booleans and arbitrary output values alone do not do this. Not Lean-verified. |
| `guard_types` | S9.R10–R16, R19–R22; Java `long` range | Parser binds ordered typed immutable guards to submitted JSON; each key is normalized; expected is nonnegative and at most MAX. Key syntax, duplicate rejection and the 16-guard public limit are external parser facts. |
| `plain`, `guarded_mode`, `nonguarded_modes` | S8 has no guard parser; S9.R13 and wrapper identity; S7 is create | Public method-to-mode mapping. Plain is exactly empty guards; the Lean overapproximation does not require parser uniqueness for safety. |
| `entry_sequence` for update | S8.E13/E15; S9.E15–E18/E20 | S9.R15/R17 recurrence plus exact ordinal slots. `nonself_count_recurrence` proves the mathematical count recurrence. Use the suffix after old `reads.length`, preserving the prior prefix. |
| `entry_sequence` for create | S7.E13/E14 and R06 | Creation's single observation is the existence read, with `answer = none`; `get` sequence stays unchanged. D-EXISTS must bind `present` to the requested snapshot key, rather than a global oracle Boolean. |
| `parent_sequence` | S7.E12; S8.E14; S9.E19; S4.E03 | Parent observations become extra-key revision dependencies. No cross-registry ordering theorem follows from these sequences. |
| `owner` equality and presence | S7.R04/R05; S8.R06/R07; S9.R08/R09; S3/S4 | Success necessity also needs public unauthorized/missing-parent exceptional cases. These are ordinary Hoare premises, not auth cryptography. |
| `owner` trim-nonempty signer | Caller binding below, in addition to owner clauses | `!owner.isBlank()` alone is insufficient: a string containing U+0000 can be nonblank yet trim to empty. This clause needs the trimmed nonempty JWT issuer/caller binding. Never mark it discharged solely by S3/S4. |
| `checked_length` | S9.E03 | Empty checked list for S7/S8 is the wrapper-to-schema interpretation. |
| `checked_source` identifiers, versions and expected equality | S9.E14, R22; S5-self.E01/E02; S5-other.E01/E02; S6-self.E01/E02; S6-other.E01/E02 | S9.E14 binds `old` answer at `b+1+nonselfBefore[i]`, or old target for self, not just expected. R22 is only sufficient on the normal-case specification; the exception partition supplies success necessity at the named boundary. |
| `checked_source` actual read key and present record | S9.E17/E18; R08/R22; registry get adapter | D-READ ordinal/key relation and immutable old-value capture required. `checked_provenance` derives the actual snapshot record, and `jml_absolute_answer_ordinal` lifts local ordinals to an arbitrary old ghost prefix. |
| `target_update` presence | S8.R06/E01; S9.R08/E01; S4.E01 | Missing-target public cases and immutable snapshot adapter. |
| `target_update` lower bound/capacity | S8.R09; S9.R20; S2-helper-invalid; S8-overflow; S9-overflow | Signed-long bounds exposed explicitly. Exhaustion/negative values must reject; no opaque validity axiom supplies successor. |
| `target_update` exact +1, preserved ID/creation | S8.E02/E03; S9.E02/E13; S1-helper-normal | `write_image` proves projection equals the existing `writtenRecord`. All images are captured values, not DTO references. |
| `target_create` absence/zero/ID/creation time | S7.R06, E02/E13 | D-EXISTS binds absence; S7-duplicate excludes a normal return when present. `creation_snapshot`, `write_image` and `no_reset` derive the concrete consequences. |
| `image_accumulator`, `image_time` | S7.E03/E04; S8.E04/E05; S9.E04/E05 | Validation and Instant rendering adapters. |
| `write_sequence` count/address/full snapshot | S7.E05/E06/A01; S8.E06/E07/A01; S9.E06/E07/A01; D-WRITE | Exactly one new put on the normalized requested address. putId/putAccum/putVersion/putCreated/putUpdated are value captures. The write counter alone is insufficient without the frame/no-hidden-writer boundary. |
| entry image nullability/type/coherence | S8.R03/R08; S9.R05/R19; erased get casts; DTO/accessor adapters | Lean Image uses nonnull String fields. All captured field values must lie in that domain, including historical accumulator/timestamps. Java service contracts alone do not ensure every old timestamp/accumulator is nonnull. Reachable-state/decoder contract must supply this, or extend the trace representation. Address/metadata equality is not assumed by the bridge. |
| `SnapshotRelation.answers` | registry get adapter: result is `answers[old(reads.length)]`, requested ID appended | D-READ/codec: at every consumed ordinal the answer is the record at that requested key in the common peer snapshot. This is a named external relation, not a conclusion of the answer-stream stub or S9. Stable detached DTOs/frames must protect previously captured values. |
| `entry_revisions`, `parent_revisions`, `auth_revisions` | No S1–S10 JML clause supplies Fabric revisions | Stub read-set registration, common snapshot, revision rank/equality correspondence and no ABA are platform adapters. Absent reads must register too. |
| `Encoding.entry_injective`, `parent_injective`, `payload_injective` | No clause in the service contracts | Canonical keys/String value equality and lossless payload representation. Entry vs extras are different model namespaces; nonce/parent separation is separate. Injection is an explicit structure field, not a custom Lean axiom. |
| `.extra` empty entry footprint, primary read/write, extraWrites | No Java entry-service clause | Named policy for other modeled ledger writers; `extra_primary_read/write` expose it. Additional writes are atomic and each distinct extra revision increases once. A blind extra-only writer without the specified primary read needs a different encoding or model extension. |
| `AuthContract` DID presence, nonce absence, nonce write, namespace separation | JWT caller source (below); no service JML case | Explicit schema boundary. It is NOT a premise used by `end_to_end`, and no JWT or nonce-policy theorem is claimed. `nonceImage` is descriptive data; the ledger models extra-key revisions, not the stored nonce payload. |
| `PerCallContracts` normal-return implication | The 37 cases of `contracts-v4.json` plus the typed runtime domain of the adapters | A normal case with successful guard values in `requires` does NOT establish necessity. Until the cases and their proofs are checked externally, this implication is a named, unproved cross-tool premise. |
| `PlatformOrigins` and `permit` interpretation | No Java service clauses | Actual snapshots are earlier prefixes of this same ordered run; real accept/reject corresponds to captured-revision validation and permission. Any caller/serialization/event failure makes permission false and discards all buffered writes. No in-memory rollback assertion. |
| `HonestCheck.observed`, `pins` | No service clauses | Actual present registry record with accumulator and version at each check prefix; check on that same record. Every guard pin uses that prefix. `honest_present_record_transport` preserves the full record and projected version. |
| `CheckFidelity`, `AuthenticatedFidelity` | No service clauses | Credential identity, persistent nonrevocation interpretation, issuer authenticity and exact intended payload effects are external. In EndToEndSpec, `realizes` is the authenticated payload's intended semantic effect, not durable revocation by both calls. One call may reject. |

The public exceptions are listed case by case in the contracts. Creation has invalid input,
missing parent, unauthorized and duplicate cases. A plain update has invalid input, missing
entry, missing parent, unauthorized and negative or exhausted version cases. A guarded update
adds an invalid guard list, the first absent non-self guard, the first mismatching self or
non-self guard, and overflow after every guard matched; a first-failure case requires the
earlier guards to succeed. Adapter faults, type errors on erased returns, unsupported `String`
values, allocation failure, reflection and concurrent heap mutation are outside these cases
unless the runtime boundary covers them. Proofs of the sufficient cases plus this list are not
a machine-checked exhaustiveness theorem.

## Where the signer comes from

The source supports the narrower signer premise. In
[`JwtValidator.authenticate`](../../src/main/java/io/github/snktibor/vdr/logic/JwtValidator.java),
line 104 obtains `issuerDid` through `requireText`, and line 121 returns it in a
`JwtPrincipal`. `requireText` (line 413) calls `JsonUtil.trimToNull` and rejects null, and
[`JsonUtil.trimToNull`](../../src/main/java/io/github/snktibor/vdr/util/JsonUtil.java)
(line 151) applies Java's `trim()` and rejects the empty result.
[`VdrContract.requireSignerDid`](../../src/main/java/io/github/snktibor/vdr/contract/VdrContract.java)
(line 577) passes that principal's signer to the entry service. The real caller therefore
supplies a trimmed, nonempty issuer, provided the caller, the JVM and the model agree. This is
an observation about the source, not a proved theorem about the JWT caller or the DID grammar.

The same validator reads the DID document at line 107, checks that the nonce is new at line
185 and buffers the nonce write at line 191, before the service runs.
[`BaseRegistry`](../../src/main/java/io/github/snktibor/vdr/ledger/BaseRegistry.java)
implements `get` and `exists` with `getStringState` and serializes the value before
`putStringState`. This explains the observation and write schema of the bridge; it does not
prove decoding, a stable snapshot, MVCC, nonce rollback or the deployed semantics. Grouping
the reads of other keys independently in `capturedExtras` is enough because validation is a
conjunction over keys; it says nothing about the physical order of calls across registries.

The event clauses of the guarded update include the payload keys (S9.E12, and likewise S7.E11
and S8.E12). The bridge does not treat events or returned objects as commit verdicts. The full
target image is represented and proved exact; event delivery and a general event schema are
outside the targets.
