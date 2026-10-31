import Model

namespace Reconstruction

theorem readsOk_iff (s : State) (t : Tx) :
    readsOk s t = true ↔ ∀ p ∈ t.reads, s p.1 = p.2 := by
  simp [readsOk, List.all_eq_true]

theorem step_mono (s : State) (t : Tx) (k : Key) : s k ≤ step s t k := by
  unfold step applyWrites
  split <;> simp_all
  split <;> omega

theorem step_eq_of_commit (s : State) (t : Tx) (h : readsOk s t = true) :
    step s t = applyWrites s t := by
  simp [step, h]

theorem final_mono : FinalMonoSpec := by
  intro s ts a b hab k
  induction b with
  | zero =>
    have : a = 0 := by omega
    subst a
    exact Nat.le_refl _
  | succ b ih =>
    by_cases heq : a = b + 1
    · subst a; exact Nat.le_refl _
    · have hab' : a ≤ b := by omega
      exact Nat.le_trans (ih hab') (step_mono _ _ k)

theorem strict_committed_write_growth : StrictGrowthSpec := by
  intro s ts i k hc hw
  change stateAt s ts i k < step (stateAt s ts i) (ts i) k
  rw [step_eq_of_commit _ _ hc]
  simp [applyWrites, hw]

theorem stale_never_commits : StaleNeverCommitsSpec := by
  intro s ts a i k v hai hr hstale hc
  have heq := (readsOk_iff _ _).mp hc (k, v) hr
  have hm := final_mono s ts a i hai k
  simp only at heq
  omega

theorem endorse_eq {peer : State} {c : GuardedCall} {t : Tx}
    (h : endorse peer c = some t) : t = endorsedTx peer c := by
  unfold endorse at h
  split at h
  · exact (Option.some.inj h).symm
  · cases h

theorem endorse_id {peer : State} {c : GuardedCall} {t : Tx}
    (h : endorse peer c = some t) : t.id = c.id := by
  rw [endorse_eq h]
  rfl

theorem tas_spec : TasSpec := by
  intro peer c t he s
  rw [endorse_eq he, readsOk_iff]
  simp [endorsedTx]

theorem no_write_of_equal_versions (s : State) (ts : Stream) (a b k : Nat)
    (heq : stateAt s ts b k = stateAt s ts a k)
    (j : Nat) (haj : a ≤ j) (hjb : j < b) (hc : commits s ts j) :
    k ∉ (ts j).writes := by
  intro hw
  have h₁ := final_mono s ts a j haj k
  have h₂ := strict_committed_write_growth s ts j k hc hw
  have h₃ := final_mono s ts (j + 1) b (by omega) k
  omega

theorem window_closed : WindowClosedSpec := by
  intro s ts chk use peer c _hbefore he hp hc p hpg
  have hg := ((tas_spec peer c (ts use) he (stateAt s ts use)).mp hc).2 p hpg
  have eqv : stateAt s ts use p.1 = stateAt s ts chk p.1 := hg.trans (hp p hpg)
  exact ⟨eqv, fun j haj hjb hjc => no_write_of_equal_versions s ts chk use p.1 eqv j haj hjb hjc⟩

theorem revoked_persistent (R₀ : Cred → Prop) (rev : Revocations)
    (s : State) (ts : Stream) (a b : Nat) (x : Cred) (hab : a ≤ b) :
    revokedAt R₀ rev s ts a x → revokedAt R₀ rev s ts b x := by
  intro hr
  rcases hr with hr | ⟨j, ⟨hja, hc⟩, hjr⟩
  · exact Or.inl hr
  · exact Or.inr ⟨j, ⟨by omega, hc⟩, hjr⟩

theorem check_holds_at_use : CheckHoldsSpec := by
  intro R₀ rev s ts chk use c own other hwf hs hbefore hc hr
  rcases hs.endorsed with ⟨peer, he⟩
  rcases hs.guardsOther with ⟨v, hguard⟩
  have hwindow := (window_closed s ts chk use peer c hbefore he hs.pinned hc
    (other.1, v) hguard).2
  rcases hr with hr | ⟨j, ⟨hjuse, hjc⟩, hjrev⟩
  · exact hs.checked (Or.inl hr)
  · by_cases hjchk : j < chk
    · exact hs.checked (Or.inr ⟨j, ⟨hjchk, hjc⟩, hjrev⟩)
    · exact hwindow j (by omega) hjuse hjc (hwf (ts j) other hjrev)

theorem no_bifurcation : NoBifurcationSpec := by
  intro R₀ rev s ts chkA chkB iA iB cA cB xA xB hwf hA hB hids hbeforeA hbeforeB hboth
  rcases hboth with ⟨hcA, hcB⟩
  have hne : iA ≠ iB := by
    intro heq
    rcases hA.endorsed with ⟨peerA, heA⟩
    rcases hB.endorsed with ⟨peerB, heB⟩
    have hidA := endorse_id heA
    have hidB := endorse_id heB
    apply hids
    rw [heq] at hidA
    exact hidA.symm.trans hidB
  by_cases hlt : iA < iB
  · exact check_holds_at_use R₀ rev s ts chkB iB cB xB xA hwf hB hbeforeB hcB
      (Or.inr ⟨iA, ⟨hlt, hcA⟩, hA.revokesOwn⟩)
  · exact check_holds_at_use R₀ rev s ts chkA iA cA xA xB hwf hA hbeforeA hcA
      (Or.inr ⟨iB, ⟨by omega, hcB⟩, hB.revokesOwn⟩)

end Reconstruction
