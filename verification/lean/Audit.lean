import Lean
import Branches
import SkipProofs
import SkipExamples
import Examples

open Lean Elab Command

/- Enumerate actual theorem constants, including generated ones, rather than a hand-picked list. -/
run_cmd do
  let env ← getEnv
  let names := env.constants.toList.filterMap fun (n, info) =>
    if (n.toString.splitOn ".").contains "Reconstruction" then
      match info with
      | .thmInfo _ => some n
      | _ => none
    else none
  for n in names.mergeSort (fun a b => a.toString ≤ b.toString) do
    logInfo m!"AUDIT_THEOREM {n}"
    elabCommand (← `(#print axioms $(mkIdent n)))
  for (n, info) in env.constants.toList do
    if (n.toString.splitOn ".").contains "Reconstruction" then
      match info with
      | .axiomInfo _ => throwError "Custom reconstruction axiom: {n}"
      | _ => pure ()

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
