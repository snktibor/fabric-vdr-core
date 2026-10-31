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
    (n.toString.splitOn ".").contains "Reconstruction"
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

#check (Reconstruction.final_mono : Reconstruction.FinalMonoSpec)
#check (Reconstruction.strict_committed_write_growth : Reconstruction.StrictGrowthSpec)
#check (Reconstruction.stale_never_commits : Reconstruction.StaleNeverCommitsSpec)
#check (Reconstruction.tas_spec : Reconstruction.TasSpec)
#check (Reconstruction.window_closed : Reconstruction.WindowClosedSpec)
#check (Reconstruction.check_holds_at_use : Reconstruction.CheckHoldsSpec)
#check (Reconstruction.no_bifurcation : Reconstruction.NoBifurcationSpec)
#check (Reconstruction.rejected_has_prior_committed_writer : Reconstruction.PriorWriterSpec)
#check (Reconstruction.at_most_one : Reconstruction.FreshPairSpec)
#check (Reconstruction.skip_state_simulation : Reconstruction.SkipStateSimulationSpec)
#check (Reconstruction.skip_commit_simulation : Reconstruction.SkipCommitSimulationSpec)
#check (Reconstruction.skip_revocation_simulation : Reconstruction.SkipRevocationSimulationSpec)
#check (Reconstruction.skip_no_bifurcation : Reconstruction.SkipNoBifurcationSpec)
#check (Reconstruction.skip_rejects : Reconstruction.SkipRejectsSpec)
