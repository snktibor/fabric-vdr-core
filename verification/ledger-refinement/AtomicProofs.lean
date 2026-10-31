import AtomicSpec
import LedgerProofs

namespace LedgerRefinement.Atomic
open Reconstruction

theorem atomic_writes : AtomicWritesSpec := by
  intro s t k
  refine ⟨rfl, rfl, ?_⟩
  by_cases hk : k ∈ t.extraWrites
  · simp [apply, hk]
  · cases hm : t.proposal.mode <;> simp [apply, LedgerRefinement.apply, hm, hk]

theorem step_revision_mono (s : Ledger) (t : Transaction) (permit : Bool) (k : Key) :
    s.revision k ≤ (step s t permit).revision k := by
  have h := LedgerRefinement.step_revision_mono s t.proposal permit k
  cases ha : accepted s t.proposal permit with
  | false => simp [step, ha]
  | true => simpa [step, LedgerRefinement.step, apply, ha] using h

theorem step_revision_stable (s : Ledger) (t : Transaction) (permit : Bool) (k : Key)
    (h : s.revision k = (step s t permit).revision k) :
    s.entries k = (step s t permit).entries k := by
  cases ha : accepted s t.proposal permit with
  | false => simp [step, ha]
  | true =>
    have hb : s.revision k = (LedgerRefinement.step s t.proposal permit).revision k := by
      simpa [step, LedgerRefinement.step, apply, ha] using h
    simpa [step, LedgerRefinement.step, apply, ha] using
      LedgerRefinement.step_revision_stable s t.proposal permit k hb

theorem revision_mono (s : Ledger) (ts : Transactions) (permit : Nat → Bool)
    (a b : Nat) (hab : a ≤ b) (k : Key) :
    (stateAt s ts permit a).revision k ≤ (stateAt s ts permit b).revision k := by
  induction b with
  | zero =>
    have : a = 0 := by omega
    subst a
    exact Nat.le_refl _
  | succ b ih =>
    by_cases he : a = b + 1
    · subst a; exact Nat.le_refl _
    · exact Nat.le_trans (ih (by omega)) (step_revision_mono _ _ _ k)

theorem revision_stability (s : Ledger) (ts : Transactions) (permit : Nat → Bool)
    (a b k : Nat) (hab : a ≤ b)
    (he : (stateAt s ts permit a).revision k = (stateAt s ts permit b).revision k) :
    (stateAt s ts permit a).entries k = (stateAt s ts permit b).entries k := by
  induction b with
  | zero =>
    have : a = 0 := by omega
    subst a
    rfl
  | succ b ih =>
    by_cases ha : a = b + 1
    · subst a; rfl
    · have hab' : a ≤ b := by omega
      have h₁ := revision_mono s ts permit a b hab' k
      have h₂ := step_revision_mono (stateAt s ts permit b) (ts b) (permit b) k
      have hbe : (stateAt s ts permit a).revision k =
          (stateAt s ts permit b).revision k := by
        change (stateAt s ts permit a).revision k =
          (step (stateAt s ts permit b) (ts b) (permit b)).revision k at he
        omega
      exact (ih hab' hbe).trans (step_revision_stable _ _ _ k (hbe.symm.trans he))

theorem current_reads_equal_snapshot (s : Ledger) (ts : Transactions) (permit : Nat → Bool)
    (origins : Origins s ts permit) (i : Nat) (hc : commits s ts permit i)
    (k : Key) (hk : k ∈ entryReads (ts i).proposal) :
    (stateAt s ts permit i).entries k = (ts i).proposal.peer.entries k := by
  rcases origins i with ⟨a, hai, hpeer⟩
  have hv := (revision_reads _ _ ((acceptance_iff _ _ _).mp hc).2.2).1 k hk
  rw [hpeer] at hv ⊢
  exact (revision_stability s ts permit a i k hai hv.symm).symm

theorem no_reset : NoResetSpec := by
  intro s ts permit ho i hc hm
  have ht := current_reads_equal_snapshot s ts permit ho i hc (ts i).proposal.call.target
    (by simp [entryReads, hm])
  rw [ht]
  have he := ((acceptance_iff _ _ _).mp hc).2.1
  simpa [eligible, hm, Option.isNone_iff_eq_none] using he

theorem invariant : InvariantSpec := by
  intro s ts permit hs n
  induction n with
  | zero => exact hs
  | succ n ih =>
    change WellFormed (step _ _ _)
    unfold step
    split
    · exact apply_wellformed _ _ ih ((acceptance_iff _ _ _).mp ‹_›).2.1
    · exact ih

theorem reject : RejectSpec := by
  intro s ts permit i hp
  simp [commits, stateAt, step, accepted, hp]

theorem state_simulation : StateSimulationSpec := by
  intro s ts permit _hwf ho n
  induction n with
  | zero => rfl
  | succ n ih =>
    have hr : accepted (stateAt s ts permit n) (ts n).proposal (permit n) = true ∨
        accepted (stateAt s ts permit n) (ts n).proposal (permit n) = false := by
      cases h : accepted (stateAt s ts permit n) (ts n).proposal (permit n) <;> simp
    rcases hr with hc | hc
    · have he := ((acceptance_iff _ _ _).mp hc).2.1
      have hreads := current_reads_equal_snapshot s ts permit ho n hc
      have habs := projected_reads _ _ he hreads
      have happ := projected_apply _ _ he hreads
      simp only [stateAt, step, ↓reduceIte, skipStateAt, allowance, hc]
      rw [← ih]
      exact happ.trans (Reconstruction.step_eq_of_commit _ _ habs).symm
    · simp [stateAt, step, hc, skipStateAt, allowance, ih]

theorem commit_simulation : CommitSimulationSpec := by
  intro s ts permit hwf ho i
  constructor
  · intro hc
    refine ⟨hc, ?_⟩
    rw [← state_simulation s ts permit hwf ho i]
    exact projected_reads _ _ ((acceptance_iff _ _ _).mp hc).2.1
      (current_reads_equal_snapshot s ts permit ho i hc)
  · intro hc
    exact hc.1

theorem revocation_simulation : RevocationSimulationSpec := by
  intro R₀ effects s ts permit hwf ho n x
  simp only [revokedAt, skipRevokedAt, commit_simulation s ts permit hwf ho,
    revokes_simulation, abstractStream]

theorem honest_transport {R₀ : Cred → Prop} {effects : Effects} {s : Ledger}
    {ts : Transactions} {permit : Nat → Bool} {chk use : Nat} {own other : Cred}
    (hwf : WellFormed s) (ho : Origins s ts permit)
    (hs : HonestStep R₀ effects s ts permit chk use own other) :
    SkipGuardedStep R₀ (abstractRevokes effects) (project s) (abstractStream ts)
      (allowance s ts permit) chk (ts use).proposal.call (abstractStream ts use) own other := by
  constructor
  · intro hr
    exact hs.checked ((revocation_simulation R₀ effects s ts permit hwf ho chk other).mpr hr)
  · intro g hg
    exact (hs.pinned g hg).trans
      (congrFun (state_simulation s ts permit hwf ho chk) g.1)
  · exact hs.guardsOther
  · exact ⟨project (ts use).proposal.peer, abstract_endorsement _ hs.update hs.endorsed⟩
  · exact (revokes_simulation _ _ _).mp hs.revokesOwn

theorem no_bifurcation : NoBifurcationSpec := by
  intro R₀ effects s ts permit chkA chkB iA iB xA xB hwf ho hA hB hids hca hcb hboth
  exact skip_no_bifurcation R₀ (abstractRevokes effects) (project s) (abstractStream ts)
    (allowance s ts permit) chkA chkB iA iB (ts iA).proposal.call (ts iB).proposal.call xA xB
    (abstract_rev_wf effects) (honest_transport hwf ho hA) (honest_transport hwf ho hB)
    hids hca hcb ⟨(commit_simulation s ts permit hwf ho iA).mp hboth.1,
      (commit_simulation s ts permit hwf ho iB).mp hboth.2⟩

theorem committed_update_exact (s : Ledger) (ts : Transactions) (permit : Nat → Bool)
    (ho : Origins s ts permit) (i : Nat) (hc : commits s ts permit i)
    (hm : (ts i).proposal.mode = .update) (r : Record)
    (hr : (stateAt s ts permit i).entries (ts i).proposal.call.target = some r) :
    (stateAt s ts permit (i + 1)).entries (ts i).proposal.call.target =
      some ⟨r.version + 1, (ts i).proposal.accumulator⟩ ∧
    0 ≤ r.version ∧ r.version + 1 ≤ maxLong ∧
    project (stateAt s ts permit (i + 1)) (ts i).proposal.call.target =
      project (stateAt s ts permit i) (ts i).proposal.call.target + 1 := by
  have hs := current_reads_equal_snapshot s ts permit ho i hc (ts i).proposal.call.target
    (by simp [entryReads, hm])
  have hp : (ts i).proposal.peer.entries (ts i).proposal.call.target = some r := hs.symm.trans hr
  have he := ((acceptance_iff _ _ _).mp hc).2.1
  rcases ((eligible_update_iff _ hm).mp he).1 with ⟨r', hr', h0, hmax⟩
  have heq : r' = r := Option.some.inj (hr'.symm.trans hp)
  subst r'
  have hv := (arithmetic r.version).2.2 h0 hmax
  have hc' : accepted (stateAt s ts permit i) (ts i).proposal (permit i) = true := hc
  have hw : (stateAt s ts permit (i + 1)).entries (ts i).proposal.call.target =
      some ⟨r.version + 1, (ts i).proposal.accumulator⟩ := by
    simp [stateAt, step, hc', apply, LedgerRefinement.apply, hm, writtenRecord, hp]
  refine ⟨hw, h0, hv.2, ?_⟩
  simpa [project, hw, hr] using hv.1

end LedgerRefinement.Atomic
