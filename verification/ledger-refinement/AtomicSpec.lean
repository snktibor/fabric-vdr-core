import AtomicModel

namespace LedgerRefinement.Atomic
open Reconstruction

def AtomicWritesSpec : Prop :=
  ∀ s t k,
    (apply s t).entries k = (LedgerRefinement.apply s t.proposal).entries k ∧
    (apply s t).revision k = (LedgerRefinement.apply s t.proposal).revision k ∧
    (apply s t).extraRevision k =
      if k ∈ t.extraWrites ∨ (t.proposal.mode = .extra ∧ k = t.proposal.call.target)
      then s.extraRevision k + 1 else s.extraRevision k

def InvariantSpec : Prop :=
  ∀ s ts permit, WellFormed s → ∀ n, WellFormed (stateAt s ts permit n)

def NoResetSpec : Prop :=
  ∀ s ts permit, Origins s ts permit → ∀ i,
    commits s ts permit i → (ts i).proposal.mode = .create →
      (stateAt s ts permit i).entries (ts i).proposal.call.target = none

def StateSimulationSpec : Prop :=
  ∀ s ts permit, WellFormed s → Origins s ts permit → ∀ n,
    project (stateAt s ts permit n) =
      skipStateAt (project s) (abstractStream ts) (allowance s ts permit) n

def CommitSimulationSpec : Prop :=
  ∀ s ts permit, WellFormed s → Origins s ts permit → ∀ i,
    commits s ts permit i ↔
      skipCommits (project s) (abstractStream ts) (allowance s ts permit) i

def RevocationSimulationSpec : Prop :=
  ∀ R₀ effects s ts permit, WellFormed s → Origins s ts permit → ∀ n x,
    revokedAt R₀ effects s ts permit n x ↔
      skipRevokedAt R₀ (abstractRevokes effects) (project s)
        (abstractStream ts) (allowance s ts permit) n x

def RejectSpec : Prop :=
  ∀ s ts permit i, permit i = false → ¬ commits s ts permit i ∧
    stateAt s ts permit (i + 1) = stateAt s ts permit i

def NoBifurcationSpec : Prop :=
  ∀ R₀ effects s ts permit chkA chkB iA iB xA xB,
    WellFormed s → Origins s ts permit →
    HonestStep R₀ effects s ts permit chkA iA xA xB →
    HonestStep R₀ effects s ts permit chkB iB xB xA →
    (ts iA).proposal.call.id ≠ (ts iB).proposal.call.id → chkA ≤ iA → chkB ≤ iB →
    ¬ (commits s ts permit iA ∧ commits s ts permit iB)

end LedgerRefinement.Atomic
