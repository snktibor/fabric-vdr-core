import Lean
import Branches
import Examples
import SkipExamples
import Witnesses

open Lean Elab Command

run_cmd do
  let env ← getEnv
  let selected := fun (n : Name) =>
    (n.toString.splitOn ".").contains "LedgerRefinement" ||
    (n.toString.splitOn ".").contains "GuardedProtocol"
  let names := env.constants.toList.filterMap fun (n, info) =>
    if selected n then
      match info with
      | .thmInfo _ => some n
      | _ => none
    else none
  for n in names.mergeSort (fun a b => a.toString ≤ b.toString) do
    logInfo m!"AUDIT_THEOREM {n}"
    elabCommand (← `(#print axioms $(mkIdent n)))
  for (n, info) in env.constants.toList do
    if selected n then
      match info with
      | .axiomInfo _ => throwError "Unexpected trusted declaration: {n}"
      | _ => pure ()

#check (LedgerRefinement.arithmetic : LedgerRefinement.ArithmeticSpec)
#check (LedgerRefinement.endorsement : LedgerRefinement.EndorsementCorrectedSpec)
#check (LedgerRefinement.frozen_endorsement_refuted : ¬ LedgerRefinement.EndorsementSpec)
#check (LedgerRefinement.creation : LedgerRefinement.CreationSpec)
#check (LedgerRefinement.revision_stability : LedgerRefinement.RevisionStabilitySpec)
#check (LedgerRefinement.invariant : LedgerRefinement.InvariantSpec)
#check (LedgerRefinement.state_simulation : LedgerRefinement.StateSimulationSpec)
#check (LedgerRefinement.commit_simulation : LedgerRefinement.CommitSimulationSpec)
#check (LedgerRefinement.revocation_simulation : LedgerRefinement.RevocationSimulationSpec)
#check (LedgerRefinement.reject : LedgerRefinement.RejectSpec)
#check (LedgerRefinement.no_reset : LedgerRefinement.NoResetSpec)
#check (LedgerRefinement.no_bifurcation : LedgerRefinement.NoBifurcationSpec)
#check (LedgerRefinement.Atomic.atomic_writes : LedgerRefinement.Atomic.AtomicWritesSpec)
#check (LedgerRefinement.Atomic.invariant : LedgerRefinement.Atomic.InvariantSpec)
#check (LedgerRefinement.Atomic.no_reset : LedgerRefinement.Atomic.NoResetSpec)
#check (LedgerRefinement.Atomic.state_simulation : LedgerRefinement.Atomic.StateSimulationSpec)
#check (LedgerRefinement.Atomic.commit_simulation : LedgerRefinement.Atomic.CommitSimulationSpec)
#check (LedgerRefinement.Atomic.revocation_simulation : LedgerRefinement.Atomic.RevocationSimulationSpec)
#check (LedgerRefinement.Atomic.reject : LedgerRefinement.Atomic.RejectSpec)
#check (LedgerRefinement.Atomic.no_bifurcation : LedgerRefinement.Atomic.NoBifurcationSpec)

#check (GuardedProtocol.final_mono : GuardedProtocol.FinalMonoSpec)
#check (GuardedProtocol.strict_committed_write_growth : GuardedProtocol.StrictGrowthSpec)
#check (GuardedProtocol.stale_never_commits : GuardedProtocol.StaleNeverCommitsSpec)
#check (GuardedProtocol.tas_spec : GuardedProtocol.TasSpec)
#check (GuardedProtocol.window_closed : GuardedProtocol.WindowClosedSpec)
#check (GuardedProtocol.check_holds_at_use : GuardedProtocol.CheckHoldsSpec)
#check (GuardedProtocol.no_bifurcation : GuardedProtocol.NoBifurcationSpec)
#check (GuardedProtocol.rejected_has_prior_committed_writer : GuardedProtocol.PriorWriterSpec)
#check (GuardedProtocol.at_most_one : GuardedProtocol.FreshPairSpec)
#check (GuardedProtocol.skip_state_simulation : GuardedProtocol.SkipStateSimulationSpec)
#check (GuardedProtocol.skip_commit_simulation : GuardedProtocol.SkipCommitSimulationSpec)
#check (GuardedProtocol.skip_revocation_simulation : GuardedProtocol.SkipRevocationSimulationSpec)
#check (GuardedProtocol.skip_no_bifurcation : GuardedProtocol.SkipNoBifurcationSpec)
#check (GuardedProtocol.skip_rejects : GuardedProtocol.SkipRejectsSpec)
