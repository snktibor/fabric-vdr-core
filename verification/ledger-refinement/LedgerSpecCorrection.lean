import LedgerSpec

namespace LedgerRefinement

/- Corrected `EndorsementSpec`: the parentheses keep the update-mode premise
   outside the equivalence. No transition definition changes. -/
def EndorsementCorrectedSpec : Prop :=
  ∀ p, p.mode = .update → (eligible p = true ↔
    (∃ r, p.peer.entries p.call.target = some r ∧ 0 ≤ r.version ∧ r.version < maxLong) ∧
    ∀ g ∈ p.call.guards, ∃ r, p.peer.entries g.1 = some r ∧
      0 ≤ r.version ∧ r.version.toNat = g.2)

end LedgerRefinement
