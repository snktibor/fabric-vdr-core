import SkipProofs

namespace Reconstruction.SkipExamples

def initial : State := fun _ => 0
def initiallyRevoked : Cred → Prop := fun _ => False
def rev : Revocations := fun t x => x.1 ∈ t.writes ∧ x.2 = 0
def xA : Cred := (0, 0)
def xB : Cred := (1, 0)
def callA : GuardedCall := ⟨10, 0, [(1, 0)]⟩
def callB : GuardedCall := ⟨11, 1, [(0, 0)]⟩
def txA : Tx := endorsedTx initial callA
def txB : Tx := endorsedTx initial callB
def stream : Stream
  | 0 => txA
  | 1 => txB
  | _ => noop
def allow : Nat → Bool
  | 0 => false
  | _ => true

theorem rev_wf : RevWF rev := by
  intro t x h
  exact h.1

theorem stepA : SkipGuardedStep initiallyRevoked rev initial stream allow 0 callA
    (stream 0) xA xB := by
  constructor
  · simp [skipRevokedAt, initiallyRevoked]
  · intro p hp
    have heq : p = (1, 0) := by simpa [callA] using hp
    subst p
    rfl
  · exact ⟨0, by simp [callA, xB]⟩
  · exact ⟨initial, rfl⟩
  · simp [rev, stream, txA, endorsedTx, callA, xA]

theorem stepB : SkipGuardedStep initiallyRevoked rev initial stream allow 0 callB
    (stream 1) xB xA := by
  constructor
  · simp [skipRevokedAt, initiallyRevoked]
  · intro p hp
    have heq : p = (0, 0) := by simpa [callB] using hp
    subst p
    rfl
  · exact ⟨0, by simp [callB, xA]⟩
  · exact ⟨initial, rfl⟩
  · simp [rev, stream, txB, endorsedTx, callB, xB]

theorem valid_occurrence_is_skipped :
    readsOk (skipStateAt initial stream allow 0) (stream 0) = true ∧
    ¬ skipCommits initial stream allow 0 := by
  constructor
  · rfl
  · exact (skip_rejects initial stream allow 0 rfl).1

theorem allowed_honest_occurrence_commits : skipCommits initial stream allow 1 := by
  exact ⟨rfl, rfl⟩

theorem skipped_writer_enables_later_commit :
    commits initial stream 0 ∧ ¬ commits initial stream 1 ∧
    ¬ skipCommits initial stream allow 0 ∧ skipCommits initial stream allow 1 := by
  refine ⟨rfl, ?_, valid_occurrence_is_skipped.2, allowed_honest_occurrence_commits⟩
  intro h
  change false = true at h
  cases h

theorem skipped_own_revocation_absent :
    ¬ skipRevokedAt initiallyRevoked rev initial stream allow 1 xA := by
  intro h
  rcases h with h | ⟨i, hi, hc, _⟩
  · exact h
  · have heq : i = 0 := by omega
    subst i
    exact valid_occurrence_is_skipped.2 hc

theorem permitted_own_revocation_present :
    skipRevokedAt initiallyRevoked rev initial stream allow 2 xB := by
  exact Or.inr ⟨1, by omega, allowed_honest_occurrence_commits, stepB.revokesOwn⟩

end Reconstruction.SkipExamples
