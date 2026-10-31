import LedgerSpec
import LedgerSpecCorrection
import SkipProofs

namespace LedgerRefinement
open Reconstruction

theorem arithmetic : ArithmeticSpec := by
  intro v
  constructor
  · by_cases h : 0 ≤ v ∧ v < maxLong
    · rw [nextVersion, if_pos h]
      exact ⟨fun _ => h, fun _ => rfl⟩
    · rw [nextVersion, if_neg h]
      constructor
      · intro hn; cases hn
      · intro hp; exact False.elim (h hp)
  constructor
  · by_cases h0 : 0 ≤ v
    · by_cases hm : v < maxLong
      · rw [nextVersion, if_pos ⟨h0, hm⟩]
        constructor
        · intro h; cases h
        · intro h; rcases h with h | h <;> omega
      · have hn : ¬ (0 ≤ v ∧ v < maxLong) := fun h => hm h.2
        rw [nextVersion, if_neg hn]
        exact ⟨fun _ => Or.inr (by omega), fun _ => rfl⟩
    · have hn : ¬ (0 ≤ v ∧ v < maxLong) := fun h => h0 h.1
      rw [nextVersion, if_neg hn]
      exact ⟨fun _ => Or.inl (by omega), fun _ => rfl⟩
  · intro h0 hm
    constructor <;> omega

theorem guard_matches_iff (peer : Ledger) (g : Key × Nat) :
    guardMatches peer g = true ↔ ∃ r, peer.entries g.1 = some r ∧
      0 ≤ r.version ∧ r.version.toNat = g.2 := by
  cases h : peer.entries g.1 <;> simp [guardMatches, h]

theorem eligible_update_iff (p : Proposal) (hm : p.mode = .update) :
    eligible p = true ↔
    (∃ r, p.peer.entries p.call.target = some r ∧ 0 ≤ r.version ∧ r.version < maxLong) ∧
    ∀ g ∈ p.call.guards, ∃ r, p.peer.entries g.1 = some r ∧
      0 ≤ r.version ∧ r.version.toNat = g.2 := by
  cases h : p.peer.entries p.call.target <;>
    simp [eligible, hm, h, nextVersion, List.all_eq_true, guard_matches_iff]

theorem acceptance_iff (s : Ledger) (p : Proposal) (permit : Bool) :
    accepted s p permit = true ↔
      permit = true ∧ eligible p = true ∧ revisionsMatch s p = true := by
  simp [accepted, and_assoc]

theorem revision_reads (s : Ledger) (p : Proposal)
    (h : revisionsMatch s p = true) :
    (∀ k ∈ entryReads p, s.revision k = p.peer.revision k) ∧
    (∀ k ∈ extraReads p, s.extraRevision k = p.peer.extraRevision k) := by
  simpa [revisionsMatch, List.all_eq_true] using h

theorem creation : CreationSpec := by
  intro s p permit hm _
  simp [apply, hm, writtenRecord]

theorem step_revision_mono (s : Ledger) (p : Proposal) (permit : Bool) (k : Key) :
    s.revision k ≤ (step s p permit).revision k := by
  unfold step
  split
  · cases hm : p.mode <;> simp [apply, hm] <;> split <;> omega
  · exact Nat.le_refl _

theorem step_revision_stable (s : Ledger) (p : Proposal) (permit : Bool) (k : Key)
    (h : s.revision k = (step s p permit).revision k) :
    s.entries k = (step s p permit).entries k := by
  cases hc : accepted s p permit with
  | false => simp only [step, hc, Bool.false_eq_true, ↓reduceIte]
  | true =>
    cases hm : p.mode with
    | extra => simp only [step, hc, ↓reduceIte, apply, hm]
    | create | update =>
      by_cases hk : k = p.call.target
      · have he : s.revision k = s.revision k + 1 := by
          simpa only [step, hc, ↓reduceIte, apply, hm, if_pos hk] using h
        omega
      · simp only [step, hc, ↓reduceIte, apply, hm, if_neg hk]

theorem revision_mono (s : Ledger) (ps : Proposals) (permit : Nat → Bool)
    (a b : Nat) (hab : a ≤ b) (k : Key) :
    (stateAt s ps permit a).revision k ≤ (stateAt s ps permit b).revision k := by
  induction b with
  | zero =>
    have : a = 0 := by omega
    subst a
    exact Nat.le_refl _
  | succ b ih =>
    by_cases he : a = b + 1
    · subst a; exact Nat.le_refl _
    · exact Nat.le_trans (ih (by omega)) (step_revision_mono _ _ _ k)

theorem revision_stability : RevisionStabilitySpec := by
  intro s ps permit a b k hab he
  induction b with
  | zero =>
    have : a = 0 := by omega
    subst a
    rfl
  | succ b ih =>
    by_cases ha : a = b + 1
    · subst a; rfl
    · have hab' : a ≤ b := by omega
      have h₁ := revision_mono s ps permit a b hab' k
      have h₂ := step_revision_mono (stateAt s ps permit b) (ps b) (permit b) k
      have hbe : (stateAt s ps permit a).revision k =
          (stateAt s ps permit b).revision k := by
        change (stateAt s ps permit a).revision k =
          (step (stateAt s ps permit b) (ps b) (permit b)).revision k at he
        omega
      exact (ih hab' hbe).trans (step_revision_stable _ _ _ k (hbe.symm.trans he))

theorem current_reads_equal_snapshot (s : Ledger) (ps : Proposals) (permit : Nat → Bool)
    (origins : Origins s ps permit) (i : Nat) (hc : commits s ps permit i)
    (k : Key) (hk : k ∈ entryReads (ps i)) :
    (stateAt s ps permit i).entries k = (ps i).peer.entries k := by
  rcases origins i with ⟨a, hai, hpeer⟩
  have hv := (revision_reads _ _ ((acceptance_iff _ _ _).mp hc).2.2).1 k hk
  rw [hpeer] at hv ⊢
  exact (revision_stability s ps permit a i k hai hv.symm).symm

theorem no_reset : NoResetSpec := by
  intro s ps permit ho i hc hm
  have ht := current_reads_equal_snapshot s ps permit ho i hc (ps i).call.target
    (by simp [entryReads, hm])
  rw [ht]
  have he := ((acceptance_iff _ _ _).mp hc).2.1
  simpa [eligible, hm, Option.isNone_iff_eq_none] using he

theorem written_record_bounded (p : Proposal) (he : eligible p = true)
    (hm : p.mode ≠ .extra) :
    0 ≤ (writtenRecord p).version ∧ (writtenRecord p).version ≤ maxLong := by
  cases hp : p.mode with
  | create => simp [writtenRecord, hp, maxLong]
  | update =>
    rcases ((eligible_update_iff p hp).mp he).1 with ⟨r, hr, h0, hmax⟩
    simp only [writtenRecord, hp, hr, Option.getD_some]
    simp only [reduceCtorEq, ↓reduceIte]
    constructor <;> omega
  | extra => exact False.elim (hm hp)

theorem apply_wellformed (s : Ledger) (p : Proposal) (hs : WellFormed s)
    (he : eligible p = true) : WellFormed (apply s p) := by
  intro k r hr
  cases hm : p.mode with
  | extra => exact hs k r (by simpa [apply, hm] using hr)
  | create | update =>
    by_cases hk : k = p.call.target
    · have heq : r = writtenRecord p := by simpa [apply, hm, hk] using hr.symm
      subst r
      exact written_record_bounded p he (by simp [hm])
    · exact hs k r (by simpa [apply, hm, hk] using hr)

theorem invariant : InvariantSpec := by
  intro s ps permit hs n
  induction n with
  | zero => exact hs
  | succ n ih =>
    change WellFormed (step _ _ _)
    unfold step
    split
    · exact apply_wellformed _ _ ih ((acceptance_iff _ _ _).mp ‹_›).2.1
    · exact ih

theorem reject : RejectSpec := by
  intro s ps permit i hp
  simp [commits, stateAt, step, accepted, hp]

theorem endorsement : EndorsementCorrectedSpec := by
  intro p hm
  exact eligible_update_iff p hm

theorem frozen_endorsement_refuted : ¬ EndorsementSpec := by
  intro h
  let empty : Ledger := ⟨fun _ => none, fun _ => 0, fun _ => 0⟩
  let p : Proposal := ⟨.create, ⟨0, 0, []⟩, empty, 0, []⟩
  have hc := (h p).mp (by intro hm; cases hm)
  rcases hc.1 with ⟨r, hr, _⟩
  cases hr

theorem projected_guard (p : Proposal) (hm : p.mode = .update) (he : eligible p = true)
    (g : Key × Nat) (hg : g ∈ p.call.guards) : project p.peer g.1 = g.2 := by
  rcases ((eligible_update_iff p hm).mp he).2 g hg with ⟨r, hr, _, hv⟩
  simpa [project, hr] using hv

theorem abstract_endorsement (p : Proposal) (hm : p.mode = .update)
    (he : eligible p = true) :
    Reconstruction.endorse (project p.peer) p.call = some (abstractTx p) := by
  have hg : guardsOk (project p.peer) p.call = true := by
    simp only [guardsOk, List.all_eq_true, beq_iff_eq]
    exact projected_guard p hm he
  simp [Reconstruction.endorse, hg, abstractTx, hm]

theorem projected_reads (s : Ledger) (p : Proposal) (he : eligible p = true)
    (hr : ∀ k ∈ entryReads p, s.entries k = p.peer.entries k) :
    readsOk (project s) (abstractTx p) = true := by
  cases hm : p.mode with
  | create | extra => simp [abstractTx, hm, readsOk]
  | update =>
    rw [readsOk_iff]
    intro g hg
    have ht : s.entries p.call.target = p.peer.entries p.call.target :=
      hr p.call.target (by simp [entryReads, hm])
    simp only [abstractTx, hm, endorsedTx, List.mem_cons] at hg
    rcases hg with hg | hg
    · subst g
      simp [project, ht]
    · have hs := hr g.1 (by simp only [entryReads, hm, List.mem_cons, List.mem_map]; exact Or.inr ⟨g, hg, rfl⟩)
      change project s g.1 = g.2
      have hp := projected_guard p hm he g hg
      simpa [project, hs] using hp

theorem projected_apply (s : Ledger) (p : Proposal) (he : eligible p = true)
    (hr : ∀ k ∈ entryReads p, s.entries k = p.peer.entries k) :
    project (apply s p) = applyWrites (project s) (abstractTx p) := by
  funext k
  cases hm : p.mode with
  | extra => simp [apply, hm, abstractTx, applyWrites, project]
  | create =>
    have hn : p.peer.entries p.call.target = none := by simpa [eligible, hm] using he
    have ht := hr p.call.target (by simp [entryReads, hm])
    by_cases hk : k = p.call.target
    · subst k
      simp [apply, hm, abstractTx, applyWrites, project, writtenRecord, ht, hn]
    · simp [apply, hm, abstractTx, applyWrites, project, hk]
  | update =>
    rcases ((eligible_update_iff p hm).mp he).1 with ⟨r, hp, h0, hmax⟩
    have ht := hr p.call.target (by simp [entryReads, hm])
    have hn := ((arithmetic r.version).2.2 h0 hmax).1
    by_cases hk : k = p.call.target
    · subst k
      simpa [apply, hm, abstractTx, endorsedTx, applyWrites, project, writtenRecord,
        ht, hp] using hn
    · simp [apply, hm, abstractTx, endorsedTx, applyWrites, project, hk]

theorem state_simulation : StateSimulationSpec := by
  intro s ps permit _hwf ho n
  induction n with
  | zero => rfl
  | succ n ih =>
    have hr : accepted (stateAt s ps permit n) (ps n) (permit n) = true ∨
        accepted (stateAt s ps permit n) (ps n) (permit n) = false := by
      cases h : accepted (stateAt s ps permit n) (ps n) (permit n) <;> simp
    rcases hr with hc | hc
    · have he := ((acceptance_iff _ _ _).mp hc).2.1
      have hreads := current_reads_equal_snapshot s ps permit ho n hc
      have habs := projected_reads _ _ he hreads
      have happ := projected_apply _ _ he hreads
      simp only [stateAt, step, ↓reduceIte, skipStateAt, allowance, hc]
      rw [← ih]
      exact happ.trans (Reconstruction.step_eq_of_commit _ _ habs).symm
    · simp [stateAt, step, hc, skipStateAt, allowance, ih]

theorem commit_simulation : CommitSimulationSpec := by
  intro s ps permit hwf ho i
  constructor
  · intro hc
    refine ⟨hc, ?_⟩
    rw [← state_simulation s ps permit hwf ho i]
    exact projected_reads _ _ ((acceptance_iff _ _ _).mp hc).2.1
      (current_reads_equal_snapshot s ps permit ho i hc)
  · intro hc
    exact hc.1

theorem revokes_simulation (effects : Effects) (p : Proposal) (x : Cred) :
    concreteRevokes effects p x ↔ abstractRevokes effects (abstractTx p) x := by
  cases hm : p.mode <;>
    simp [concreteRevokes, abstractRevokes, abstractTx, hm, endorsedTx]

theorem revocation_simulation : RevocationSimulationSpec := by
  intro R₀ effects s ps permit hwf ho n x
  simp only [revokedAt, skipRevokedAt, commit_simulation s ps permit hwf ho,
    revokes_simulation, abstractStream]

theorem abstract_rev_wf (effects : Effects) : RevWF (abstractRevokes effects) := by
  intro t x h
  exact h.1

theorem honest_transport {R₀ : Cred → Prop} {effects : Effects} {s : Ledger}
    {ps : Proposals} {permit : Nat → Bool} {chk use : Nat} {own other : Cred}
    (hwf : WellFormed s) (ho : Origins s ps permit)
    (hs : HonestStep R₀ effects s ps permit chk use own other) :
    SkipGuardedStep R₀ (abstractRevokes effects) (project s) (abstractStream ps)
      (allowance s ps permit) chk (ps use).call (abstractStream ps use) own other := by
  constructor
  · intro hr
    exact hs.checked ((revocation_simulation R₀ effects s ps permit hwf ho chk other).mpr hr)
  · intro g hg
    exact (hs.pinned g hg).trans
      (congrFun (state_simulation s ps permit hwf ho chk) g.1)
  · exact hs.guardsOther
  · exact ⟨project (ps use).peer, abstract_endorsement _ hs.update hs.endorsed⟩
  · exact (revokes_simulation _ _ _).mp hs.revokesOwn

theorem no_bifurcation : NoBifurcationSpec := by
  intro R₀ effects s ps permit chkA chkB iA iB xA xB hwf ho hA hB hids hca hcb hboth
  exact skip_no_bifurcation R₀ (abstractRevokes effects) (project s) (abstractStream ps)
    (allowance s ps permit) chkA chkB iA iB (ps iA).call (ps iB).call xA xB
    (abstract_rev_wf effects) (honest_transport hwf ho hA) (honest_transport hwf ho hB)
    hids hca hcb ⟨(commit_simulation s ps permit hwf ho iA).mp hboth.1,
      (commit_simulation s ps permit hwf ho iB).mp hboth.2⟩

end LedgerRefinement
