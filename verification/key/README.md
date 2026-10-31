# Exact-source production contracts

Run from the repository root with Java/Javac 21+ and Python 3:

```sh
python3 verification/key/run.py
```

The runner downloads the [official KeY 3.0.0 JAR](https://github.com/KeYProject/key/releases/tag/KeY-3.0.0)
and checks SHA-256 `1a398e0070c3514e9b258df7073e87a0fad5faf56f35d2e1d66f8947ef884f88`.
Use `--key-jar /path/to/key-3.0.0-exe.jar` to reuse that exact artifact and
`--output /path/to/evidence` to select the evidence directory. Defaults write to
`target/key-production`; no global KeY settings are changed.

The runner proves 15 contracts, then replays every saved proof in a fresh JVM without
proof search. `result.json` maps each full contract name to its claim, proof ID, node
count and hash. It also records source correspondence, tool/settings, assumptions and
artifact hashes. Commands, PIDs and logs are retained. A failed rerun replaces any old
success status with `FAILED`; a crash after startup leaves `RUNNING`, never a new success.

## Deductively proved

- The actual package-private `RevRegEntryVersion.next` returns mathematical `v + 1`
  for `0 <= v < Long.MAX_VALUE`, including MAX-1, with no heap changes.
- MAX and every negative long throw the actual `VdrException` with error code
  `FAILED_PRECONDITION`, preserving pre-existing heap state. Allocation of the exception
  is allowed. These three disjoint input ranges cover all Java long values.
- Both actual `RevRegEntryRecord` constructors initialize their specified fields.
  The explicit constructor preserves its supplied version, including zero; it does not
  itself enforce nonnegativity.
- All five record getters return their field without mutation. Each of the five setters
  stores its argument and changes only that field. Nullable String fields/arguments are
  explicit, including the deserialization constructor's null state.

The full production files for the version helper, exception and record are copied without
changing a byte. The exception factory and constructor bodies are expanded while proving
the version helper; they are not assumed through a self-contract. The saved proofs use
only the One Step Simplification and Set Statement builtins. No SMT or operation-contract
application is permitted by the runner's proof audit.

## Trust boundary and unproved service scope

KeY's frontend, JavaDL calculus and bundled JavaRedux models are trusted. Arithmetic uses
Java long semantics, with bigint JML postconditions. Runtime exceptions are allowed; final
fields stay on the heap. Static initialization is disabled for these classes, which have no
stateful static initializers. Resource exhaustion and concurrent execution are outside this
sequential model. JavaRedux's `Throwable` constructor uses ghost message/cause assignments;
that is the source of the Set Statement builtin.

Successful proofs use the unmodified boot library from the pinned JAR. The sole stub is
an empty interface declaring the imported Jackson annotation name: KeY ignores annotation
uses and rejects annotation declarations. It supplies no invoked methods or operation
contracts. Jackson annotation processing and serialization are not proved.

The full `RevRegEntryService` fails to load with `Type arguments found.` at its generic
collection types. [KeY lists generics as unsupported](https://www.key-project.org/applications/program-verification/).
No production generics are stripped, no transformed service is substituted, and no
shared-helper or caller contract is counted among the 15 proofs. The private exceptional
contract on `applyAccumulatorUpdate` records the intended next obligation; it is **unproved**.

Reproduce the parser limitation separately after obtaining the pinned JAR:

```sh
python3 verification/key/probe_service.py --key-jar target/key-production/tools/key-3.0.0-exe.jar
```

This probe copies the full relevant production files unchanged and adds signature-only
dependency stubs. It uses no boot override. Its process fails before any contract is loaded;
the wrapper records `NOT_PROVED_FRONTEND_REJECTED_GENERICS` and exits successfully only if
that precise limitation is observed. The probe establishes no adapter or service theorem.

## Service evidence

Source inspection shows both public updates call `applyAccumulatorUpdate`, whose first
operation computes the checked version before any setter, registry write or event. The
guarded caller checks its guards first. Creation supplies zero after checking absence.
This composition is source-audited and unit-tested, not a KeY proof.

`RevRegEntryServiceTest` covers create/duplicate behavior, both paths at MAX-1, MAX, -1 and
Long.MIN_VALUE, all five preserved target fields on rejection, zero write/event on failure,
one write/event on success and the self-guard's pre-update version. Its registries/events
are mocked. Existing validation, authorization and guard tests also run.

```sh
mvn -Dtest=RevRegEntryServiceTest test
mvn test
mvn spotless:check
mvn package -DskipTests
```

These checks do not establish live Fabric behavior, MVCC uniqueness, cryptography, JSON,
authentication, or the Lean refinement. Earlier JWT nonce staging is outside the service
frame. Arbitrary adapter failures after mutation are not promised to restore the in-memory
record; ledger rejection semantics depend on Fabric.

The workflows run Maven checks and the KeY slice on pull requests. The KeY workflow retains
the proof inputs, source manifest, saved proofs and logs as a CI artifact, including on failure.
