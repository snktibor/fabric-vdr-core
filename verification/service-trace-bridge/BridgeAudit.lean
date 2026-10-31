import Lean
import BridgeWitnesses

open Lean Elab Command

run_cmd do
  let env ← getEnv
  let selected := fun (n : Name) =>
    (n.toString.splitOn ".").contains "ServiceTraceBridge" ||
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

#check (ServiceTraceBridge.eligibility : ServiceTraceBridge.EligibilitySpec)
#check (ServiceTraceBridge.write_image : ServiceTraceBridge.WriteImageSpec)
#check (ServiceTraceBridge.read_coverage : ServiceTraceBridge.ReadCoverageSpec)
#check (ServiceTraceBridge.checked_provenance : ServiceTraceBridge.CheckedProvenanceSpec)
#check (ServiceTraceBridge.validation : ServiceTraceBridge.ValidationSpec)
#check (ServiceTraceBridge.apply_refinement : ServiceTraceBridge.ApplySpec)
#check (ServiceTraceBridge.step_refinement : ServiceTraceBridge.StepSpec)
#check (ServiceTraceBridge.run_refinement : ServiceTraceBridge.RunSpec)
#check (ServiceTraceBridge.commit_refinement : ServiceTraceBridge.CommitSpec)
#check (ServiceTraceBridge.no_reset : ServiceTraceBridge.NoResetSpec)
#check (ServiceTraceBridge.safety : ServiceTraceBridge.SafetySpec)
#check (ServiceTraceBridge.end_to_end : ServiceTraceBridge.EndToEndSpec)
