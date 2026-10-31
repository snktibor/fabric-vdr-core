# Extension: rejection for reasons other than MVCC

Real transactions can fail for reasons other than a stale read: authentication, policy, a
malformed request or an exception in the chaincode. This extension shows that such
rejections do not break the safety result, provided they leave the state unchanged. It
imports the unchanged `Model.lean`. `SkipModel.lean` holds its definitions and target
statements, and `frozen/skip-semantic.sha256` seals that file and this one.

The result is about the abstract model. Whether the Java code, Fabric's error handling and
the accumulator really behave this way is a separate question.

## Definitions

- `allow : Nat → Bool` is arbitrary: it need not be honest, fair or independent of the
  state. At each stream position, `skipStateAt` either runs the usual MVCC step or leaves
  the state unchanged.
- `skipCommits` requires both `allow i = true` and successful MVCC validation.
  `skipRevokedAt` counts only these committed occurrences.
- `SkipGuardedStep` checks and pins against the prefixes of this extended run and otherwise
  matches the protocol of the base model.
- The erasure replaces every disallowed transaction with `noop = ⟨0, [], []⟩` at the same
  index; it never removes or reorders a position. The noop passes validation but writes
  nothing, so under `RevWF` it revokes nothing. The kept occurrences then have the same
  state, transaction and outcome as in the extended run, and every check still comes before
  its use. These facts are proved, not assumed.

## Target statements

1. `skip_state_simulation : SkipStateSimulationSpec`: every state of the extended run equals
   the corresponding state of the erased base run.
2. `skip_commit_simulation : SkipCommitSimulationSpec`: an allowed occurrence commits in one
   run if and only if it commits in the other.
3. `skip_revocation_simulation : SkipRevocationSimulationSpec`: under `RevWF`, both runs give
   the same revoked history, even though the noops commit.
4. `skip_no_bifurcation : SkipNoBifurcationSpec`: the reciprocal safety theorem holds with
   arbitrary extra rejections and interleavings. The proof moves the actual steps and
   committed occurrences into the base theorem.
5. `skip_rejects : SkipRejectsSpec`: a disallowed occurrence never commits and keeps the
   previous state, even when MVCC would accept it.

The same kernel check and axiom whitelist apply. No theorem claims that a real non-MVCC
failure preserves the state; that premise needs evidence from the implementation and Fabric.
No liveness or exactly-one-commit property is claimed. `SkipExamples.lean` shows an
MVCC-valid transaction being skipped next to allowed honest transactions in the same run,
and that skipping an earlier writer can let a later transaction commit, so the commits of
the extended run are not a subset of the unfiltered run's commits.
