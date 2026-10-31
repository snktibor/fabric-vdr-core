import LedgerModel

namespace LedgerRefinement
open Reconstruction

def ArithmeticSpec : Prop :=
  ∀ v, (nextVersion v = some (v + 1) ↔ 0 ≤ v ∧ v < maxLong) ∧
    (nextVersion v = none ↔ v < 0 ∨ maxLong ≤ v) ∧
    (0 ≤ v → v < maxLong → (v + 1).toNat = v.toNat + 1 ∧ v + 1 ≤ maxLong)

def EndorsementSpec : Prop :=
  ∀ p, p.mode = .update → eligible p = true ↔
    (∃ r, p.peer.entries p.call.target = some r ∧ 0 ≤ r.version ∧ r.version < maxLong) ∧
    ∀ g ∈ p.call.guards, ∃ r, p.peer.entries g.1 = some r ∧
      0 ≤ r.version ∧ r.version.toNat = g.2

def CreationSpec : Prop :=
  ∀ s p permit, p.mode = .create → accepted s p permit = true →
    (apply s p).entries p.call.target = some ⟨0, p.accumulator⟩ ∧
    (apply s p).revision p.call.target = s.revision p.call.target + 1

def RevisionStabilitySpec : Prop :=
  ∀ s ps permit a b k, a ≤ b →
    (stateAt s ps permit a).revision k = (stateAt s ps permit b).revision k →
    (stateAt s ps permit a).entries k = (stateAt s ps permit b).entries k

def InvariantSpec : Prop :=
  ∀ s ps permit, WellFormed s → ∀ n, WellFormed (stateAt s ps permit n)

def StateSimulationSpec : Prop :=
  ∀ s ps permit, WellFormed s → Origins s ps permit → ∀ n,
    project (stateAt s ps permit n) =
      skipStateAt (project s) (abstractStream ps) (allowance s ps permit) n

def CommitSimulationSpec : Prop :=
  ∀ s ps permit, WellFormed s → Origins s ps permit → ∀ i,
    commits s ps permit i ↔
      skipCommits (project s) (abstractStream ps) (allowance s ps permit) i

def RevocationSimulationSpec : Prop :=
  ∀ R₀ effects s ps permit, WellFormed s → Origins s ps permit → ∀ n x,
    revokedAt R₀ effects s ps permit n x ↔
      skipRevokedAt R₀ (abstractRevokes effects) (project s)
        (abstractStream ps) (allowance s ps permit) n x

def RejectSpec : Prop :=
  ∀ s ps permit i, permit i = false → ¬ commits s ps permit i ∧
    stateAt s ps permit (i + 1) = stateAt s ps permit i

def NoResetSpec : Prop :=
  ∀ s ps permit, Origins s ps permit → ∀ i,
    commits s ps permit i → (ps i).mode = .create →
      (stateAt s ps permit i).entries (ps i).call.target = none

def NoBifurcationSpec : Prop :=
  ∀ R₀ effects s ps permit chkA chkB iA iB xA xB,
    WellFormed s → Origins s ps permit →
    HonestStep R₀ effects s ps permit chkA iA xA xB →
    HonestStep R₀ effects s ps permit chkB iB xB xA →
    (ps iA).call.id ≠ (ps iB).call.id → chkA ≤ iA → chkB ≤ iB →
    ¬ (commits s ps permit iA ∧ commits s ps permit iB)

end LedgerRefinement
