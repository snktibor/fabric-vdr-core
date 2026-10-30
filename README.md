# fabric-vdr-core

Java chaincode implementing a **Verifiable Data Registry (VDR) for AnonCreds v2** on
Hyperledger Fabric, together with a Cucumber-based end-to-end test suite that runs
against a real Fabric network.

The registry stores the public objects an AnonCreds ecosystem needs — DID documents,
schemas, credential definitions, revocation registry definitions and revocation
registry entries — in five separate world-state namespaces. It deliberately stores
**no credentials and no per-holder revocation data** (no revoked-index lists, no tails
files): revocation state is a single accumulator value published by the issuer — a
**VB20 pairing-based accumulator** (Vitto–Biryukov,
[eprint 2020/777](https://eprint.iacr.org/2020/777)), the tails-free scheme the
AnonCreds v2 reference implementation actually ships.

> **Status / scope honesty.** AnonCreds v2 is **not a finalised specification**; this
> project targets the current reference implementation
> ([`hyperledger/anoncreds-v2-rs`](https://github.com/hyperledger/anoncreds-v2-rs)) and
> should be read as a *reference VDR for AnonCreds v2*, not a conformant implementation
> of a finished standard. The chaincode provides registry CRUD plus chaincode events on
> every state change, an accumulator history endpoint, and audit timestamps on records;
> there is still **no pagination** (the `list*` transactions return entire namespaces).
> `type` values are allowlisted and `accumValue`/`publicKeys` are format-validated —
> see [Wire encodings and type identifiers](#wire-encodings-and-type-identifiers) for
> exactly what is (and is not) guaranteed. Note the naming: the reference implements
> the VB20 accumulator; ALLOSAUR (eprint 2022/1362) is an unimplemented multi-party
> witness-update layer on top of it, and this VDR deliberately does not claim it.

## Reproduce

[![CI](https://github.com/snktibor/fabric-vdr-core/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/snktibor/fabric-vdr-core/actions/workflows/ci.yml?query=branch%3Amain)

This repository is the artifact of the TDK paper *Decentralized verifiable credential
revocation with atomic test-and-set for cross-organizational settings* (Tibor Sinka, BME, 2026).
The [CI run on `main`](https://github.com/snktibor/fabric-vdr-core/actions/workflows/ci.yml?query=branch%3Amain)
shows every check below on a GitHub runner: the unit tests, the Lean proofs, the E2E suite on a
live Fabric network, and a short scalability run, whose CSVs it keeps as an artifact.

With only Docker installed:

```bash
docker build -f reproduce.Dockerfile -t fabric-vdr-reproduce .
docker run --rm fabric-vdr-reproduce
```

This runs the unit tests, builds the Lean model and proofs of
[`argos-formal`](https://github.com/snktibor/argos-formal) at the commit the paper cites, checks
that each of its eight mutations breaks a proof, and prints the committed scalability results.

The E2E suite and the measurement need a live Fabric network, so they run on the host. With
JDK 17, Maven, Docker, [Fablo 2.6.0](https://github.com/hyperledger-labs/fablo),
[just](https://github.com/casey/just) and [elan](https://github.com/leanprover/elan) installed:

```bash
just reproduce --smoke   # all of the above, the E2E suite, and a four-minute measurement
just reproduce           # the same with the full matrix, about an hour per topology
```

| Claim | Recipe | Evidence |
|---|---|---|
| The services, registries, contract routing and JWT checks behave as specified | `just test` | 158 JUnit 5 tests, under a minute |
| In the Lean model, two parties that guard each other's registries do not both commit, and a guarded check still holds when the revocation commits | `just formal` | `lake build --wfail` over `argos-formal`; `Axioms.lean` fails the build on a `sorry` or an extra axiom |
| Leaving out one part of the design or one hypothesis breaks a proof | `just formal` | `mutations/check.sh`: each of the eight mutations breaks the build |
| On a live network, the unguarded check-then-revoke race lets both revocations commit (B1u) and the guarded update lets exactly one commit (B1) | `just e2e` | 43 Cucumber scenarios, A0 to B1 among them ([below](#concurrency-scenarios--part-of-the-default-run)) |
| Cost of the guards, throughput under load, and rejections under contention (W1 to W3) | `just benchmark` | `results/<topology>/<run>/summary.csv` ([`results/`](results/README.md), [`bench/`](bench/README.md)) |
| Under a crashed peer, organisation, Raft leader or Raft majority, at most one revocation of each mutually guarded pair commits, and Org1 alone cannot change the ledger | `just robustness` | `pairs.csv`, `faults.csv` and `solo.csv` of the `3org-raft` runs in [`results/`](results/README.md); `bench/analyze-faults.py` summarises them |

**What does not reproduce exactly.** Latency and throughput depend on the machine: the paper's
numbers come from the runs in [`results/`](results/README.md), each with its `environment.txt`,
and the CI runner only shows that the measurement runs. In B1 either issuer may win, and no
assertion names the winner. In the robustness runs, how long a Raft fault stalls the clients depends on
their commit-status deadline (`COMMIT_STATUS_S`), so the paper reports those faults at 60 s and at 2 s. The credx integration test (`AnonCredsDemoIT`) is skipped unless
`VDR_DEMO_STAGE3_DIR` points at the output of
[`anoncreds-vdr-demo`](https://github.com/snktibor/anoncreds-vdr-demo).

## Contents

- [Reproduce](#reproduce)
- [Prerequisites](#prerequisites)
- [Build](#build)
- [Unit tests](#unit-tests)
- [End-to-end tests against a live network](#end-to-end-tests-against-a-live-network)
- [Transaction reference](#transaction-reference)
- [Wire encodings and type identifiers](#wire-encodings-and-type-identifiers)
- [Chaincode events](#chaincode-events)
- [Record timestamps](#record-timestamps)
- [Identifiers](#identifiers)
- [Authentication and authorization](#authentication-and-authorization)
- [Error codes](#error-codes)
- [Architecture](#architecture)
- [Related repositories](#related-repositories)
- [License](#license)

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | **17** | The build targets Java 17 (`maven.compiler.release=17`); the unit-test toolchain also requires a JDK 17 runtime (the pinned JaCoCo 0.8.12 agent cannot read newer JDKs' class files). |
| Maven | 3.9+ | |
| Docker | recent | Needed for the reproduce image and the end-to-end suite (Docker Desktop or OrbStack; Apple Silicon works — see [`anoncreds-vdr-demo`](https://github.com/snktibor/anoncreds-vdr-demo), which holds the network recipe). |
| [just](https://github.com/casey/just) | 1.21+ | Runs the recipes in the `justfile`. Optional: each recipe is a few plain commands. |
| [Fablo](https://github.com/hyperledger-labs/fablo) | 2.6.0 | Brings up the network for `just e2e` and `just benchmark`. |
| [elan](https://github.com/leanprover/elan) | recent | Installs the Lean toolchain `argos-formal` pins, for `just formal`. |

## Build

```bash
mvn clean package
```

This produces the shaded chaincode jar at `target/chaincode.jar`. The `Dockerfile` in
the repo root wraps it into the Chaincode-as-a-Service (CCaaS) image
(`eclipse-temurin:17-jre` base) that the network recipe in [`anoncreds-vdr-demo`](https://github.com/snktibor/anoncreds-vdr-demo) deploys.

Code style is [google-java-format, AOSP style], enforced by Spotless:

```bash
mvn spotless:check    # verify
mvn spotless:apply    # fix
```

## Unit tests

```bash
mvn test
```

Runs the 158 JUnit 5 unit tests (services, registries, contract routing, JWT
validation, and three for the scalability harness). No network or Docker required.

Use `mvn test`, not a bare `mvn verify`, when no network is running: the `verify`
phase also runs the integration tests below, which need a live Fabric network.

## End-to-end tests against a live network

The E2E suite (43 Cucumber scenarios, six of them
[concurrency scenarios](#concurrency-scenarios--part-of-the-default-run))
drives the deployed chaincode through the Fabric
Gateway SDK. It expects the network recipe from
[`anoncreds-vdr-demo`](https://github.com/snktibor/anoncreds-vdr-demo), with this repository checked out **inside** it:

```
anoncreds-vdr-demo/
├── bootstrap.sh
├── fablo-config.json
├── fablo-target/          # generated by Fablo at network start
└── fabric-vdr-core/       # ← this repository
```

1. Bring up the network (builds the chaincode image and deploys it):

   ```bash
   cd ..            # into anoncreds-vdr-demo
   ./bootstrap.sh   # build images + fablo up
   ```

2. Run the suite from this repository:

   ```bash
   mvn -Pe2e verify
   ```

Without `anoncreds-vdr-demo`, `just e2e` brings up the same network from
[`bench/fablo/1org-solo.json`](bench/fablo/1org-solo.json), runs the suite against it and tears
it down.

The suite connects as `Org1` admin to `localhost:7041` on channel `my-channel1` and
reads the crypto material generated by Fablo. The crypto path defaults to
`../fablo-target/fabric-config/crypto-config/peerOrganizations/org1.example.com` and
can be overridden for other checkout layouts with the system property
`vdr.e2e.cryptoPath` or the environment variable `VDR_E2E_CRYPTO_PATH`:

```bash
mvn -Pe2e verify -Dvdr.e2e.cryptoPath=/path/to/crypto-config/peerOrganizations/org1.example.com
```

### Concurrency scenarios — part of the default run

`mvn -Pe2e verify` runs the six scenarios tagged `@concurrency` along with the rest, and
all six pass. To run only those six:

```bash
mvn -Pe2e verify -Dit.test=RunCucumberIT -Dcucumber.filter.tags='@concurrency'
```

The scenarios live in
[`revregentry_concurrency.feature`](src/test/resources/features/revregentry_concurrency.feature)
and measure what Fabric's commit-time MVCC validation does and does not serialise for
revocation registry updates:

| Scenario | What it shows | Today |
|---|---|---|
| A0 | control: two updates to one registry entry, applied one after the other | green |
| A1 | two updates to the **same** entry, endorsed against the same state and released together: exactly one commits, the other is invalidated with `MVCC_READ_CONFLICT` | green |
| A2 | the same race with each update guarded by the very registry it writes — a compare-and-swap on itself: the outcome is A1's, because the write set already decides that race | green |
| B0 | control: two independent registries, each issuer re-checks the other before revoking, run **sequentially** | green |
| B1u | characterisation of the same check-then-revoke run **concurrently**: both revocations commit as `VALID` and both registries end at version 1, because the client-side re-check forms no read set and the two transactions therefore have disjoint read and write sets | green |
| B1 | the same race with each revocation made conditional on the other registry: exactly one commits as `VALID`, the other is invalidated with `MVCC_READ_CONFLICT`, and one registry ends at version 1 with the other still at 0 | green |

B1u asserts what the unconditional update path measurably does, not what one might want it
to do: "at most one of the two revocations takes effect" is not a property of
`updateRevRegEntry`, because the "is the other registry still unrevoked?" check is a
client-side query and commit-time validation has nothing to compare. The two controls keep
that reading honest — B0 runs the same operations sequentially, where the re-check does
hold, and A1 shows the same machinery detecting a real `MVCC_READ_CONFLICT` when there is
one.

B1 closes that window, and it is the same fixture, the same observations and the same
release machinery as B1u — the only difference is that each update names the other registry
as a guard. A guarded update re-reads every registry it names **inside the submitted
transaction** and compares it with the version the caller observed, so that key joins the
transaction's read set. The two transactions, which in B1u overlapped nowhere, now each
read what the other writes; whichever is validated second finds its read set stale and is
invalidated. The client-side check has become a ledger-enforced precondition, re-checked at
commit time by the same MVCC validation A1 exercises on a single registry. Neither issuer
is favoured — which one wins varies between runs, and no assertion names a winner.

A guarded update can also be refused before it is ordered, with `FAILED_PRECONDITION`, when
the registry it guards moved before the proposal was simulated. That is the sequential
case, measured by the scenarios tagged `@guard` in
[`revregentry_guarded_update.feature`](src/test/resources/features/revregentry_guarded_update.feature).
In B1 every proposal is endorsed against the same committed state before any of them is
submitted, so no guard can have moved by then and the refusal necessarily falls to
commit-time validation instead.

The build defines no default tag filter: `-Dcucumber.filter.tags` takes any tag expression
on the command line, and without it every scenario runs.

## Transaction reference

Contract name: `VdrContract`. Every return value is a string, as is every parameter
except `createRevRegDef`'s numeric `maxCredNum`; JSON payloads are passed as
serialized JSON strings, and every response is canonical (deterministically ordered)
JSON. `SUBMIT` transactions write state and require a DID-JWT (`jwt` parameter, see
[below](#authentication-and-authorization)); the sole exception is `createDid`, which
takes no JWT by design — DID registration is the open bootstrap step (see
[Trust model](#trust-model)). `EVALUATE` transactions are unauthenticated reads. The
`list*` transactions return a JSON array of `{"id": ..., "value": ...}` objects and
scan the whole namespace (no pagination).

### DID documents

| Transaction | Type | Parameters | Returns |
|---|---|---|---|
| `createDid` | SUBMIT | `id` (DID Core syntax, see [Identifiers](#identifiers)), `didDocumentJson` | created DID document |
| `getDid` | EVALUATE | `id` | DID document |
| `updateDid` | SUBMIT | `id`, `didDocumentJson`, `jwt` | updated DID document |
| `deactivateDid` | SUBMIT | `id`, `jwt` | DID document with `status: INACTIVE` |
| `listDids` | EVALUATE | — | array of all DID registry entries |

> **Warning:** DID deactivation is currently **irreversible**. An INACTIVE DID can no
> longer sign accepted JWTs, so every JWT-gated operation of that issuer — including
> `updateRevRegEntry` on its revocation registries — is permanently blocked.

### Schemas

| Transaction | Type | Parameters | Returns |
|---|---|---|---|
| `createSchema` | SUBMIT | `id` (DID URL rooted at the issuer DID, see [Identifiers](#identifiers)), `name`, `version`, `attrNamesJson` (JSON string array), `jwt` | created schema record |
| `getSchema` | EVALUATE | `id` | schema record |
| `listSchemas` | EVALUATE | — | array of all schema entries |

### Credential definitions

| Transaction | Type | Parameters | Returns |
|---|---|---|---|
| `createCredDef` | SUBMIT | `id` (DID URL rooted at the issuer DID, see [Identifiers](#identifiers)), `schemaId`, `type` (allowlisted, see [below](#wire-encodings-and-type-identifiers)), `tag`, `publicMetadataJson` (JSON object), `jwt` | created credential definition record |
| `getCredDef` | EVALUATE | `id` | credential definition record |
| `listCredDefs` | EVALUATE | — | array of all credential definition entries |

### Revocation registry definitions

| Transaction | Type | Parameters | Returns |
|---|---|---|---|
| `createRevRegDef` | SUBMIT | `id` (DID URL rooted at the issuer DID, see [Identifiers](#identifiers)), `credDefId`, `type` (allowlisted), `publicKeysJson` (exact shape, see [below](#wire-encodings-and-type-identifiers)), `maxCredNum` (number), `jwt` | created revocation registry definition record |
| `getRevRegDef` | EVALUATE | `id` | revocation registry definition record |
| `listRevRegDefs` | EVALUATE | — | array of all revocation registry definition entries |

### Revocation registry entries

The entry is the only mutable record type: it holds the issuer's current accumulator
value for one revocation registry definition, and is stored keyed by that parent
definition's id. It has no identifier of its own — exactly one entry exists per
definition, so the parent's DID URL addresses it unambiguously (see
[Identifiers](#identifiers)).

| Transaction | Type | Parameters | Returns |
|---|---|---|---|
| `createRevRegEntry` | SUBMIT | `revRegDefId`, `accumValue` (compressed G1 hex, see [below](#wire-encodings-and-type-identifiers)), `jwt` | created entry record |
| `updateRevRegEntry` | SUBMIT | `revRegDefId`, `accumValue`, `jwt` | updated entry record (audit `version` incremented) |
| `updateRevRegEntryIfGuardsUnchanged` | SUBMIT | `revRegDefId`, `accumValue`, `guardsJson` (see [below](#conditional-updates-guards)), `jwt` | `{entry, guards}`: the updated entry record plus the version each guarded registry was checked at |
| `getRevRegEntry` | EVALUATE | `revRegDefId` | entry record |
| `getRevRegEntryHistory` | EVALUATE | `revRegDefId` | JSON array of all entry revisions, **oldest first**, every element the same shape: `{txId, timestamp, deleted, accumValue, version}` (a hypothetical future deletion sets `deleted: true` and both record fields to `null`); empty array if no entry was ever created |

Clients observe accumulator changes by subscribing to the
[`RevRegEntryUpdated` chaincode event](#chaincode-events); `getRevRegEntryHistory`
resolves the accumulator value that was current at a past point (entry updates
overwrite the value in place, which VB20 witness/proof checks against historical
accumulator states need).

#### Conditional updates: guards

`updateRevRegEntryIfGuardsUnchanged` writes the same accumulator as `updateRevRegEntry`,
but only if the registries it names are still where the caller last observed them.
`guardsJson` is a JSON array with one element per guarded registry — an entry's id and the
version it is expected to stand at:

```json
[
  {"revRegDefId": "<issuer-did>/anoncreds/v2/REV_REG_DEF/<UUIDv7>", "expectedVersion": 3},
  {"revRegDefId": "<other-did>/anoncreds/v2/REV_REG_DEF/<UUIDv7>", "expectedVersion": 0}
]
```

The list must be non-empty and hold at most 16 elements; each element must be an object
with exactly those two fields and no others; `expectedVersion` must be a non-negative JSON
integer that fits a signed 64-bit value (`"3"` and `3.0` are rejected); and no registry may
appear twice. The payload is parsed strictly: a duplicate JSON key is rejected rather than
silently resolved last-wins, and so is anything following the array, so the text the token
signs has exactly one reading. Every one of these is `INVALID_ARGUMENT`, decided before any
registry is read.

The transaction then runs in two parts. The **test** reads each guarded entry *inside the
submitted transaction* and compares its version with the submitted one, in list order, the
first mismatch refusing the whole transaction. The **set** replaces the target's
accumulator and increments its `version`, exactly as `updateRevRegEntry` does. Nothing is
written to any registry other than the target: a guard that is not the target is read and
nothing more, and when the target names itself the write is the set part, never the guard.
No guard's parent definition is read either. Reading each guard inside the transaction is
what admits its key to the read set, so a guard that still held at endorsement but is
overwritten before this transaction commits is caught a second time, by Fabric's
commit-time MVCC validation — that is the race the
[concurrency scenarios](#concurrency-scenarios--part-of-the-default-run) measure.

A registry may guard **itself**, which makes the update a compare-and-swap on its own
version: the write lands only if the entry is still the one the new accumulator was
computed from. That closes the last-write-wins gap two writers of the same registry have
when they update one after the other on the unconditional path, each from the version it
read first, and the later write silently overwrites the earlier revocation. (In a *race*
on one registry a self-guard changes nothing — the write set already decides that one, as
scenario A2 shows.) A self-guard costs no second read — the target entry is already
loaded — and the version it is tested against is the one *before* this transaction's
mutation. It does not make the accumulator monotone: an owner can still read the current
version and write an older value back under it.

The response is a JSON object with two members:

```json
{
  "entry": {"revRegDefId": "…", "accumValue": "…", "createdAt": "…", "updatedAt": "…", "version": 4},
  "guards": [
    {"revRegDefId": "<target id>", "checkedVersion": 3},
    {"revRegDefId": "<other id>", "checkedVersion": 0}
  ]
}
```

| Field | What it is |
|---|---|
| `entry.version` | the target's version **after** the mutation |
| `guards[i].checkedVersion` | the version a guard was **found at** when the test decided — read out of world state inside the transaction, never the number the caller submitted |

`guards` follows the submitted order and carries no field named `version`: the two numbers
mean different things and are deliberately named apart. For a self-guard `checkedVersion` is
the pre-mutation version, exactly one less than `entry.version`.

**Chaining.** A follow-up conditional update takes the target's own expected version from
the previous response's `entry.version`, and each external guard's expected version from
that response's `guards[i].checkedVersion`. The latter is not an assumption that the guarded
registry has stayed put — a successful update guarantees nothing past its own commit — it is
fail-safe: if the registry has moved since, the guard refuses, because every write
increments `version`; if it has not, the version match is a state match, so the off-chain
check made against that state still holds. Submitting a `checkedVersion` where
`entry.version` belongs is refused, which is the correct answer.

Which code comes back when:

| Condition | Code |
|---|---|
| blank `revRegDefId`, malformed `accumValue`, or a `guardsJson` breaking any rule above | `INVALID_ARGUMENT` |
| the target's entry or its parent revocation registry definition does not exist | `NOT_FOUND` |
| the signer does not own the target's parent definition | `UNAUTHORIZED` |
| a guarded registry has **no entry** — the identifier names nothing at all, or names a definition whose genesis entry was never created | `NOT_FOUND`, **including** under `expectedVersion: 0` |
| a guarded entry stands at a version other than the submitted one | `FAILED_PRECONDITION` |

An absent record is not a version-zero record: "does not exist" is not a state a caller
could have observed and pinned, and treating it as a match would let a guard naming a typo
pass unconditionally. The ownership check on the target runs before any guard is read, so a
caller that does not own the target gets `UNAUTHORIZED` whatever it named as a guard, and
the guard-specific codes only ever reach a caller authorized to write. Every check precedes
the mutation: a refused transaction writes nothing and emits no event.

`updateRevRegEntry` is unchanged and not deprecated — for an issuer whose update depends on
no other registry it remains the correct and cheaper call. It gives **no cross-registry
guarantee**: two issuers who each re-check the other's registry and then revoke on that path
can both commit, because a client-side query forms no read set and commit-time validation
has nothing to compare (measured, not hypothetical — scenario B1u). The conditional path is
**opt-in**, and what it guarantees is one thing: that this caller's guards were unchanged
from the state it observed up to its own commit. "At most one of a set of mutually
conditional revocations takes effect" therefore holds only if **every** competing writer
takes the guarded path and names the others as guards; if one writes on the unconditional
path, its revocation and a guarded one can both take effect.

## Wire encodings and type identifiers

### Type allowlist

`type` values are validated against an allowlist (never a blocklist), so retired or
foreign scheme names — e.g. the AnonCreds v1 `CL_ACCUM` — are illegal by construction:

| Field | Legal values |
|---|---|
| `CredDef.type` | `ANONCREDS_V2/BBS/1.0`, `ANONCREDS_V2/PS/1.0` |
| `RevRegDef.type` | `ANONCREDS_V2/VB20/1.0` |

These identifiers are **invented by this VDR** (`<family>/<scheme-token>/<version>`):
the reference implementation defines no wire-level scheme constant — its statements
are serde-tagged by Rust type name and its only scheme strings are transcript
domain-separation labels. The scheme tokens are the reference's module names
(`credx::knox::bbs`, `::ps`, `::accumulator::vb20`), the only stable names it offers.
If the specification later canonicalizes identifiers, they can be added to the
allowlist alongside these (single-sourced in `io.github.snktibor.vdr.logic.VdrTypes`).

### accumValue

The VB20 accumulator is a **BLS12-381 G1 point**. On the wire and on the ledger it is
the 48-byte ZCash-format compressed encoding (big-endian x-coordinate; flag bits in
the most significant byte: `0x80` compressed, `0x40` infinity, `0x20` y-sign) written
as **exactly 96 lowercase hex characters** (`^[0-9a-f]{96}$`). This is byte-identical
to what the reference implementation's own JSON serialization produces for
`vb20::Accumulator` (credx 0.2.1 → blstrs_plus 0.8.18 serde, which is test-vector
pinned and identical across both blsful backends) — an anoncreds-v2-rs client can
consume the value with `serde_json` directly, no adapter.

### publicKeys

Exactly one field, nothing else:

```json
{"revocationVerifyingKey": "<192 lowercase hex characters>"}
```

The value is the VB20 accumulator verifying key — a **BLS12-381 G2 point** in 96-byte
compressed form (the reference's `IssuerPublic.revocation_verifying_key`). Payloads
with missing, extra, or non-string fields are rejected.

### Validation scope (what the chaincode does and does not check)

On-chain validation is **format-only**: length, lowercase-hex charset, the compression
flag must be set, and the identity element (infinity flag) is rejected outright — an
accumulator or verifying key is never legitimately the identity. Uppercase, `0x`
prefixes, base64, and uncompressed encodings are all rejected so canonical ledger JSON
stays byte-stable. The chaincode does **not** verify on-curve or subgroup membership —
that requires a pairing library it deliberately does not carry; the reference
deserializer enforces membership client-side on every read. The normative layer is the
**compressed byte encoding**; lowercase hex is this registry's textual projection of
those bytes, chosen to match the reference serde exactly.

## Chaincode events

Every `SUBMIT` transaction emits exactly one chaincode event (Fabric supports one event
per transaction, and a second `setEvent` would silently replace the first — the emitter
is one-shot and fails the transaction rather than allow that). Events are delivered to
Gateway subscribers **with the committed block**: a transaction that fails endorsement
or validation delivers nothing, so an observed event always reflects committed state.
Payloads are canonical JSON (sorted keys, deterministic across endorsers).

| Event | Emitted by | Payload |
|---|---|---|
| `DidCreated` | `createDid` | `{id}` |
| `DidUpdated` | `updateDid` | `{id}` |
| `DidDeactivated` | `deactivateDid` | `{id}` |
| `SchemaCreated` | `createSchema` | `{id, issuerId}` |
| `CredDefCreated` | `createCredDef` | `{id, issuerId, schemaId}` |
| `RevRegDefCreated` | `createRevRegDef` | `{id, issuerId, credDefId}` |
| `RevRegEntryCreated` | `createRevRegEntry` | `{revRegDefId, accumValue, version}` |
| `RevRegEntryUpdated` | `updateRevRegEntry`, `updateRevRegEntryIfGuardsUnchanged` | `{revRegDefId, accumValue, version}` |

`RevRegEntryUpdated` is the event holders of VB20-accumulator-backed credentials must
subscribe to: after
every accumulator change a holder has to refresh their witness or they can no longer
produce a non-revocation proof. The payload carries the new accumulator value (a
fixed-size digest) so no follow-up query is needed. It carries no timestamp: the only
time value available on chain is the submitter's unattested channel-header time, which
a subscriber receiving it "from the chain" would reasonably mistake for network-attested
— `version` orders entry states, and subscribers observe delivery themselves.
A [conditional update](#conditional-updates-guards) emits that same `RevRegEntryUpdated`
event with the same payload as an unconditional one, so the event stays a pure
accumulator-change signal; the evidence that the guards held goes to the caller in the
response, not to witness-refresh subscribers. `EVALUATE` transactions emit nothing.

## Record timestamps

Every record carries `createdAt`, and the two mutable record types (DID documents and
revocation registry entries) also carry `updatedAt`, as ISO-8601 strings. Both are
service-controlled and always present: any client-supplied value in a submitted payload
is overwritten, and reads never return `null` for them. An `updateDid` carries the
original `createdAt` over to the replacement document, so an update cannot restamp the
record's creation time.

> **Trust caveat.** These values (and the `timestamp` in history revisions) come from the
> submitting client's channel-header transaction timestamp: deterministic across peers,
> but **chosen by the submitter and not attested by the network**. They are audit
> metadata only — do not build freshness or ordering guarantees on them. Ordering within
> a revocation registry is what the entry `version` is for.

## Identifiers

AnonCreds object ids are **DID URLs rooted at the publishing issuer's DID**, following the
convention every registered AnonCreds objects method uses — `did:indy` writes
`<did>/anoncreds/<version>/<TYPE>/<...>`, the Hiero method
`<publisher-did>/anoncreds/<version>/<TYPE>/<topic>`:

```
<issuer-did>/anoncreds/v2/<TYPE>/<UUIDv7>
```

| Object | Format |
|---|---|
| Schema | `<issuer-did>/anoncreds/v2/SCHEMA/<UUIDv7>` |
| Credential definition | `<issuer-did>/anoncreds/v2/PUBLIC_CRED_DEF/<UUIDv7>` |
| Revocation registry definition | `<issuer-did>/anoncreds/v2/REV_REG_DEF/<UUIDv7>` |
| DID | `did:<method>:<method-specific-id>` (DID Core syntax, **enforced**; no method privileged) |

For example, for the issuer `did:vdr:018f6c02-3a5e-7cbb-9d3e-5f2a4b6c8d0e`:

```
did:vdr:018f6c02-3a5e-7cbb-9d3e-5f2a4b6c8d0e/anoncreds/v2/SCHEMA/018f6c05-1b22-7aa0-8c31-9e77d0f6a4b1
```

Segment by segment:

- **The issuer DID roots the id**, so an identifier names its own issuer: resolving one never
  needs a second lookup to learn who published it. A DID never contains `/` (DID Core), so the
  id splits unambiguously into issuer and path at its first slash. Object-creating transactions
  take no separate issuer argument — the id is *the sole source of the issuer*, read back out of
  it at that first slash — and that issuer must match the verified token signer, so no caller
  can mint an identifier rooted at a DID it does not control.
- **`v2`** versions the AnonCreds *object shapes*, not this registry's method specification.
  Both registered methods sit at `v0`, which denotes the legacy Indy-era shapes; this registry
  publishes AnonCreds v2 objects (see the [type allowlist](#type-allowlist)).
- **`PUBLIC_CRED_DEF`** is preferred over `did:indy`'s `CLAIM_DEF`, which is Indy ledger
  transaction vocabulary for a CL-signature credential definition — an AnonCreds v1 construct
  this registry rejects by construction. It also states what is actually published: only the
  public half of the credential definition ever reaches the ledger.
- **`<UUIDv7>`** carries an embedded millisecond timestamp, so ids sort by creation time.

Ids are client-minted; the chaincode validates the grammar on create and never generates one.
Revocation registry entries have no id of their own: they are addressed by their parent
revocation registry definition's DID URL, exactly as the AnonCreds revocation status list is
resolved through its definition. The time dimension other methods encode in an entry id is
served here by [`getRevRegEntryHistory`](#revocation-registry-entries).

## Authentication and authorization

Identity is two-level:

- **Transport / network level:** the caller must be a valid Fabric client (X.509 MSP
  identity); this is standard Fabric and is not interpreted by the chaincode.
- **SSI level:** every state-changing transaction (except `createDid`) takes a compact
  **DID-JWT** and is authorized against the on-ledger DID registry, fully
  deterministically (no external calls, all time checks use the transaction timestamp
  with 30 s clock-skew tolerance):
  - `alg` must be **ES256**; the `kid` header selects a `verificationMethod` of the
    issuer's DID document (fragment `#key-1` style kids are expanded).
  - The verification method must be `JsonWebKey2020` with an EC **P-256** JWK — this is
    currently required of *every* verification method in a DID document.
  - `iss` names the issuer DID, which must exist and be `ACTIVE` on the ledger — the
    signing key is resolved from that document, so a valid signature *is* the proof of
    issuance; `iat`, `nbf` and `exp` are mandatory and checked against the transaction
    timestamp.
  - Tokens are **deployment-bound, transaction-bound and one-time use**:
    - `aud` must contain the fixed audience `vdr-network:vdrchaincode`.
    - The `txd` claim must equal the base64url (unpadded) SHA-256 digest of the
      length-prefixed method name and argument list of the exact invocation being
      authorized — the plain method name (e.g. `createSchema`) followed by the submitted
      string arguments in order, **excluding** the trailing `jwt` argument, each encoded
      as 4-byte big-endian UTF-8 byte length + bytes. The single-sourced definition
      (constants and digest code) is `io.github.snktibor.vdr.logic.JwtBinding`. A token minted
      for one operation, or for different arguments, is rejected on any other. Arguments
      are digested **as submitted**, with no canonicalization step in between, so a
      JSON-valued argument is bound byte for byte — for
      `updateRevRegEntryIfGuardsUnchanged`, the exact `guardsJson` text the client sends,
      whitespace and key order included. Serialize the guard list **once** and hand that
      same string to both the token and the invocation; serializing it a second time for
      the call yields a different digest, and the transaction is rejected as out of scope.
    - `jti` is mandatory; each (issuer DID, `jti`) pair is accepted **once**. On success
      it is consumed into the `JWTNONCE` world-state namespace (with the token expiry, so
      entries are prunable by future housekeeping), and any later transaction presenting
      the same pair fails as a replay. Fabric MVCC serializes concurrent submissions of
      the same pair. The `jwt` must always be the transaction's final argument.
  - Mutations are **owner-gated**: the signer DID must match the `ownerId` recorded on
    the target object (for entries: on the parent revocation registry definition).
  - On create, the object id must be a DID URL rooted at a well-formed issuer DID, and that
    issuer — read out of the id itself, since no create transaction declares one separately —
    must equal the signer DID, so a published id always names the DID that actually
    authorized it.

### Trust model

- **Open DID registration (intentional).** `createDid` takes no JWT: any channel member
  may register any not-yet-taken, syntactically valid DID id, and ownership is
  self-assigned first come, first served (`ownerId` is the DID itself). Every other
  mutation is authorized against this self-registered ownership via DID-JWT. Because
  every AnonCreds object id is a DID URL rooted at its issuer's DID, taking a DID is
  also what reserves the identifier space beneath it.
- **Single-org endorsement (dev network).** The companion network defines one
  organization (`Org1MSP`, two peers) with no explicit endorsement policy, so the
  Fabric-level write gate is simply membership in `Org1`; the chaincode reads no MSP
  attributes. The DID-JWT layer described above is the application-level authorization.
- **Submitted JWTs are visible to the whole channel.** Every `jwt` argument is recorded
  in the block store and readable by all channel members. Harvesting one is useless:
  tokens are one-time (`jti` consumed on success) and scope-bound (`aud` + `txd`), so a
  captured token cannot authorize any transaction — not even a repeat of the original.
- **The transaction timestamp is client-supplied.** The timestamp used for the
  `exp`/`nbf`/`iat` checks comes from the submitting client's channel header
  (deterministic across peers, but not attested by orderer or peer clocks). Replay
  protection therefore rests on the one-time `jti` consumption, not on token expiry.

## Error codes

Failures surface as `ChaincodeException` with a stable machine-readable code:

| Code | Meaning |
|---|---|
| `INVALID_ARGUMENT` | input validation failed (blank field, malformed id or JSON, …) |
| `ALREADY_EXISTS` | an object with the given id already exists |
| `NOT_FOUND` | the requested object, or one it references, does not exist |
| `UNAUTHORIZED` | JWT validation or ownership check failed, or the acting DID is deactivated |
| `FAILED_PRECONDITION` | the request is well-formed and authorized, but world state does not meet a precondition it stated: a registry named as a guard is no longer at the version the caller submitted (see [conditional updates](#conditional-updates-guards)) |
| `INTERNAL` | a chaincode invariant was violated (only a code defect can produce this) |

`FAILED_PRECONDITION` says only that the guarded registry's `version` moved — **not** that
anything was revoked in it, and not that whatever changed bears on the credential at hand.
The chaincode computes no cryptography and compares nothing but versions, and any write to
that entry moves its version. The accompanying message names the registry and both the
found and the expected version, but the code is the stable part to branch on.

## Architecture

The chaincode follows a strict four-layer design; see the Javadoc for details.

| Layer | Package | Responsibility |
|---|---|---|
| 1 — Contract API | `contract` | transaction routing, (de)serialization, error mapping — no business logic |
| 2 — Context | `context` | per-transaction dependency wiring (`SsiContext`, `SsiServices`) |
| 3 — Business logic | `logic` | validation, JWT authentication, authorization, domain rules |
| 4 — Ledger access | `ledger` | composite-key namespaces, all `ChaincodeStub` I/O (`BaseRegistry`) |

All persisted and returned JSON is canonicalized (sorted keys) so endorsement results
are deterministic across peers. Concurrent `updateRevRegEntry` submissions against the
same registry are serialized by Fabric's MVCC validation.

## Related repositories

- [`anoncreds-vdr-demo`](https://github.com/snktibor/anoncreds-vdr-demo) — the Fabric
  network recipe this chaincode is deployed with, and an end-to-end AnonCreds v2 demo
  that publishes real `credx` objects through it.

The objects-method specification this chaincode implements, including the conditional
update (`updateRevRegEntryIfGuardsUnchanged`), is
[Hyperledger Fabric AnonCreds Method](docs/anoncreds-method.md).

To cite this software, use the metadata in [CITATION.cff](CITATION.cff).

## License

Apache License 2.0 — see [LICENSE](LICENSE). See also [CONTRIBUTING.md](CONTRIBUTING.md)
(DCO sign-off required), [SECURITY.md](SECURITY.md), [MAINTAINERS.md](MAINTAINERS.md)
and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
