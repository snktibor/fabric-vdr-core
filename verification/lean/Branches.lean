import Proofs

namespace Reconstruction

theorem stateAt_eq_of_no_writers (s : State) (ts : Stream) (n : Nat) (k : Key)
    (h : ∀ j, j < n → commits s ts j → k ∉ (ts j).writes) :
    stateAt s ts n k = s k := by
  induction n with
  | zero => rfl
  | succ n ih =>
    have hpre : ∀ j, j < n → commits s ts j → k ∉ (ts j).writes := by
      intro j hj hc
      exact h j (by omega) hc
    have heq := ih hpre
    change step (stateAt s ts n) (ts n) k = s k
    by_cases hc : readsOk (stateAt s ts n) (ts n) = true
    · rw [step_eq_of_commit _ _ hc]
      simp [applyWrites, h n (by omega) hc, heq]
    · have hbad : readsOk (stateAt s ts n) (ts n) ≠ true := hc
      simp [step, hbad, heq]

theorem rejected_has_prior_committed_writer : PriorWriterSpec := by
  classical
  intro s ts i hfresh hreject
  apply Classical.byContradiction
  intro hn
  apply hreject
  apply (readsOk_iff _ _).mpr
  intro p hp
  have hno : ∀ j, j < i → commits s ts j → p.1 ∉ (ts j).writes := by
    intro j hj hc hw
    exact hn ⟨p, hp, j, ⟨hj, hc⟩, hw⟩
  exact (stateAt_eq_of_no_writers s ts i p.1 hno).trans (hfresh p hp).symm

theorem at_most_one : FreshPairSpec := by
  intro s ts i j a b va vb hne hwa hwb hrb hra hva hvb hboth
  rcases hboth with ⟨hci, hcj⟩
  by_cases hij : i < j
  · have hm := final_mono s ts 0 i (by omega) a
    have hg := strict_committed_write_growth s ts i a hci hwa
    have hs : va < stateAt s ts (i + 1) a := by
      change s a ≤ stateAt s ts i a at hm
      omega
    exact stale_never_commits s ts (i + 1) j a va (by omega) hra hs hcj
  · have hm := final_mono s ts 0 j (by omega) b
    have hg := strict_committed_write_growth s ts j b hcj hwb
    have hs : vb < stateAt s ts (j + 1) b := by
      change s b ≤ stateAt s ts j b at hm
      omega
    exact stale_never_commits s ts (j + 1) i b vb (by omega) hrb hs hci

end Reconstruction
