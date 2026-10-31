import Proofs
import SkipModel

namespace Reconstruction

theorem step_noop (s : State) : step s noop = s := by
  funext k
  simp [step, noop, readsOk, applyWrites]

theorem skip_state_simulation : SkipStateSimulationSpec := by
  intro s ts allow n
  induction n with
  | zero => rfl
  | succ n ih =>
    cases ha : allow n <;>
      simp [skipStateAt, stateAt, erasedStream, ha, ih, step_noop]

theorem skip_commit_simulation : SkipCommitSimulationSpec := by
  intro s ts allow i ha
  simp [skipCommits, commits, skip_state_simulation s ts allow i, erasedStream, ha]

theorem noop_revokes_nothing (rev : Revocations) (hwf : RevWF rev) (x : Cred) :
    ¬ rev noop x := by
  intro hr
  have hm := hwf noop x hr
  simp [noop] at hm

theorem skip_revocation_simulation : SkipRevocationSimulationSpec := by
  intro R₀ rev s ts allow n x hwf
  constructor
  · intro hr
    rcases hr with hr | ⟨i, hin, hc, hrev⟩
    · exact Or.inl hr
    · have ha := hc.1
      have hc' := (skip_commit_simulation s ts allow i ha).mp hc
      have hrev' : rev (erasedStream ts allow i) x := by
        simpa [erasedStream, ha] using hrev
      exact Or.inr ⟨i, ⟨hin, hc'⟩, hrev'⟩
  · intro hr
    rcases hr with hr | ⟨i, ⟨hin, hc⟩, hrev⟩
    · exact Or.inl hr
    · cases ha : allow i with
      | false =>
        have hn : rev noop x := by simpa [erasedStream, ha] using hrev
        exact False.elim (noop_revokes_nothing rev hwf x hn)
      | true =>
        have hc' := (skip_commit_simulation s ts allow i ha).mpr hc
        have hrev' : rev (ts i) x := by simpa [erasedStream, ha] using hrev
        exact Or.inr ⟨i, hin, hc', hrev'⟩

theorem skip_guardedStep_transport
    {R₀ : Cred → Prop} {rev : Revocations} {s : State} {ts : Stream}
    {allow : Nat → Bool} {chk i : Nat} {c : GuardedCall} {own other : Cred}
    (hwf : RevWF rev)
    (hs : SkipGuardedStep R₀ rev s ts allow chk c (ts i) own other)
    (ha : allow i = true) :
    GuardedStep R₀ rev s (erasedStream ts allow) chk c
      (erasedStream ts allow i) own other := by
  have heq : erasedStream ts allow i = ts i := by simp [erasedStream, ha]
  constructor
  · intro hr
    exact hs.checked ((skip_revocation_simulation R₀ rev s ts allow chk other hwf).mpr hr)
  · intro p hp
    exact (hs.pinned p hp).trans (congrFun (skip_state_simulation s ts allow chk) p.1)
  · exact hs.guardsOther
  · rw [heq]
    exact hs.endorsed
  · rw [heq]
    exact hs.revokesOwn

theorem skip_no_bifurcation : SkipNoBifurcationSpec := by
  intro R₀ rev s ts allow chkA chkB iA iB cA cB xA xB hwf hA hB hids hbeforeA hbeforeB hboth
  have ha := hboth.1.1
  have hb := hboth.2.1
  have hA' := skip_guardedStep_transport hwf hA ha
  have hB' := skip_guardedStep_transport hwf hB hb
  have hcA := (skip_commit_simulation s ts allow iA ha).mp hboth.1
  have hcB := (skip_commit_simulation s ts allow iB hb).mp hboth.2
  exact no_bifurcation R₀ rev s (erasedStream ts allow) chkA chkB iA iB cA cB xA xB
    hwf hA' hB' hids hbeforeA hbeforeB ⟨hcA, hcB⟩

theorem skip_rejects : SkipRejectsSpec := by
  intro s ts allow i ha
  simp [skipCommits, skipStateAt, ha]

end Reconstruction
