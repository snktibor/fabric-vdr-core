# Additional-rejection extension: frozen contract

[AGENT, parent brief] After the baseline core, address the paper's state-preserving-rejection TODO with a concrete simulation. This extension is frozen before its proofs. It imports the unchanged baseline `Model.lean` and does not replace its definitions. It is an abstract safety result; Java, Fabric error handling, actual transaction filtering and accumulator correspondence remain OPEN.

[AGENT] `allow : Nat → Bool` is arbitrary, with no honesty, fairness or state-independence assumption. At every original stream position, `skipStateAt` either runs the usual sequential MVCC step or leaves state unchanged. `skipCommits` requires both `allow i = true` and successful MVCC. `skipRevokedAt` uses only these committed occurrences. `SkipGuardedStep` checks and pins this extended execution's actual prefixes and otherwise matches the baseline protocol exactly.

[AGENT] The concrete erasure replaces each disallowed transaction with `noop = ⟨0, [], []⟩` at the same index. It never reorders or removes positions. The erased noop passes baseline validation but has no writes; `RevWF` proves it revokes nothing. Retained occurrences have precisely the same state, transaction and commit outcome as the extended execution. Check-to-use prefix inequalities are unchanged. The simulation will be proved from these definitions, not assumed.

[AGENT] Frozen exact signatures are in `SkipModel.lean`:

1. `skip_state_simulation : SkipStateSimulationSpec`: equality of every extended state with the concrete erased baseline state.
2. `skip_commit_simulation : SkipCommitSimulationSpec`: equivalence of commits for each allowed occurrence.
3. `skip_revocation_simulation : SkipRevocationSimulationSpec`: equality of the persistent revoked interpretation under `RevWF`, despite erased noop commits.
4. `skip_no_bifurcation : SkipNoBifurcationSpec`: full independent-prefix reciprocal protocol safety with arbitrary additional rejections and arbitrary interleavings, by transporting actual steps and committed occurrences into the baseline theorem.
5. `skip_rejects : SkipRejectsSpec`: a disallowed occurrence never commits and preserves its predecessor state, even if MVCC would succeed.

[AGENT] Kernel compile and the same axiom whitelist apply. No theorem concludes that a real non-MVCC failure is state preserving; that implementation/Fabric premise still needs separate evidence. No liveness or exactly-one-commit property is claimed. Concrete examples will demonstrate an MVCC-valid transaction being skipped and permitted honest transactions in the same execution.
