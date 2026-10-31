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
    if (n.toString.splitOn ".").contains "GuardedProtocol" then
      match info with
      | .thmInfo _ => some n
      | _ => none
    else none
  for n in names.mergeSort (fun a b => a.toString ≤ b.toString) do
    logInfo m!"AUDIT_THEOREM {n}"
    elabCommand (← `(#print axioms $(mkIdent n)))
  for (n, info) in env.constants.toList do
    if (n.toString.splitOn ".").contains "GuardedProtocol" then
      match info with
      | .axiomInfo _ => throwError "Custom axiom in the model: {n}"
      | _ => pure ()

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
