# Independent Lean reconstruction: frozen contract

Frozen before proof search, 2026-10-25. This is newly authored reconstruction of the supplied paper, not reproduction of unavailable `argos/lean`. The exact definitions and target propositions are in `Model.lean`; `frozen/semantic.sha256` seals both this document and that file. Proof files must import the sealed model.

## Authority and scope

- [USER] Approved the overall verification argument and authorized execution ("Okay, let's go"). The operational choices below are parent-agent or reconstruction-agent decisions, informed by the paper.

- [AGENT, parent brief] Authorized Lean 4.23.0, core-distribution libraries only, independent reconstruction, full asynchronous protocol theorem, witnesses, counterexamples and axiom audit. The paper and all files outside this directory are read only.
- [AGENT, parent brief] Creation at version 0 on an absent key remains outside the strict-write core. Implementation/model correspondence stays OPEN. The persistent revoked-set interpretation does not prove accumulator cryptography or faithful Java updates.
- [AGENT] `import Std` means the standard library shipped with the pinned Lean release, no Mathlib, external packages or additional axioms. All proof terms are checked by Lean's kernel.
- [AGENT] Keys and credential indices are natural numbers; states are total functions `Key → Nat`. Every represented key is already in scope. This encoding has no absence, creation, deletion, bounded integer arithmetic, serialization, cryptography or authorization keys.

## State, validation and occurrences

- [AGENT] `Tx` has an opaque natural ID, a finite list of `(key, version)` reads, and a finite list of written keys. `readsOk` tests exact equality for every read. A successful `step` increments each written key once (including a duplicated write key only once); rejection is the identity function. There are no extra rejection mechanisms in the baseline.
- [AGENT] A stream is `Nat → Tx`. `stateAt s ts n` runs exactly the first `n` entries, sequentially. This indexes arbitrary finite prefixes of arbitrary streams, with no bounded exploration or fairness assumption. A finite list can be extended by empty no-op transactions; the extension has no effect before its end.
- [AGENT] `commits s ts i` is validation at occurrence `i`; `committed s ts n i` additionally requires `i < n`. Commit facts never use transaction membership or ID equality. Repeated identical transactions and IDs are permitted. Two calls in the main theorem have distinct IDs, which ensures different occurrence indices through endorsement.

## Endorsement and protocol

- [AGENT] A call contains ID, target, and finite guards. Endorsement rejects a mismatching guard; otherwise it constructs reads `(target, peer target) :: guards` and writes `[target]`. A guard retains exactly its pinned version, known equal to the peer's version upon successful endorsement. The target read is mandatory. The low-level constructor permits empty guards for the explicit unguarded attack. Every honest step has at least its other credential's registry guard.
- [AGENT] `GuardedStep` requires an unrevoked other credential at its actual check prefix, all guards pinned to that prefix's versions, coverage of the other credential's registry, successful endorsement at some peer state, and revocation of its own credential. The theorem separately requires each check to precede its own use. Endorsing peers may differ. Checks may differ, and either check may occur after the other transaction's position; this last case is excluded by proof if both commits are assumed, not by a scheduling hypothesis.
- [AGENT] `rev : Tx → Cred → Prop` supplies semantic revocation effects. `RevWF` explicitly requires `rev t x → x.registry ∈ t.writes` for every transaction and credential. Revocation is persistent: `revokedAt` means initially revoked or revoked by some committed occurrence in the prefix. This is an abstract history interpretation. Paper AS7/AS8 and faithful issuer updates are the assumptions needed to relate real accumulator checks to it; `RevWF` and persistence do not verify those assumptions or Java/Fabric behavior.

## Frozen targets

Exact binder order and types are in the named `*Spec` propositions at the end of `Model.lean`.

1. `final_mono : FinalMonoSpec`: every key's version is monotone between arbitrary ordered prefixes.
2. `strict_committed_write_growth : StrictGrowthSpec`: a committed occurrence strictly increases every key it writes in its immediate successor state.
3. `stale_never_commits : StaleNeverCommitsSpec`: if a read is already below a key's version at an earlier prefix, its occurrence cannot commit.
4. `tas_spec : TasSpec`: endorsed validation iff target equality and all exact guard equalities, for any validation state.
5. `window_closed : WindowClosedSpec`: committed checked-prefix pins imply unchanged guarded versions and no committed guarded-key writer between check and use.
6. `check_holds_at_use : CheckHoldsSpec`: the checked credential remains unrevoked immediately before the committed use, under explicit `RevWF`.
7. `no_bifurcation : NoBifurcationSpec`: reciprocal honest steps at independent check prefixes cannot both commit at their distinct occurrences, for arbitrary streams and interleavings. It has no initially-fresh pair assumption.
8. [AGENT, optional separate branch] `rejected_has_prior_committed_writer : PriorWriterSpec`: initially exact fresh reads plus occurrence rejection imply a prior committed writer to a read key.
9. [AGENT, optional separate branch] `at_most_one : FreshPairSpec`: mutually read/write guarding, distinct occurrences, and pins no newer than the initial state exclude two commits. This does not replace item 7.

## Non-vacuity and falsifiability

[AGENT, parent brief] Require kernel-checked concrete honest-step witnesses, a committed honest occurrence, unguarded both-commit and guarded neither-commit executions. [AGENT] Also seek two different honest check times with unrelated interleaved writes and either party winning in separate orders. Witness definitions may be added after this freeze, but may not redefine the sealed semantics. If any target is false, preserve it and report a checked counterexample with a separately proposed correction; never silently weaken it.

## Oracle and evidence

[AGENT, parent brief] No `sorry`, `admit`, `native_decide` or custom axioms. Print dependencies of every exported theorem, reject `sorryAx` and non-whitelisted axioms. Baseline whitelist is `propext` and `Quot.sound`. Any `Classical.choice` must be separately explained; the optional detectability branch may use classical extraction from failure of universal validation. Save commands, logs, exact tool hashes, frozen model/spec hashes and theorem inventory with actual statuses. Do not claim the paper's twenty-theorem development was reproduced. [AGENT] A replay script will compile all source modules afresh and inspect all exported theorem axioms and the frozen hashes.

## Possible extension, not part of this freeze

[AGENT, parent brief] After the core, if feasible, add a separately frozen state-preserving skip/erasure simulation for arbitrary extra validation rejection. Preserve committed occurrences and check-prefix ordering. This must not change the baseline or be called an implementation proof. It is not yet claimed proved.
