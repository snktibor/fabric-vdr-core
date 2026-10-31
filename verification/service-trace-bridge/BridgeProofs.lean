import BridgeSpec
import AtomicProofs

namespace ServiceTraceBridge
open Reconstruction LedgerRefinement

theorem checked_provenance : CheckedProvenanceSpec := by
  intro e c h i g hi
  rcases h.post.checked_source i g hi with ⟨o, r, v, ho, hk, hr, hv, vk, vr, ve⟩
  refine ⟨o, r, v, ho, hk, hr, hv, vk, vr, ve, ?_⟩
  have hs := h.snapshot.answers _ o ho
  simpa [hk, hr] using hs

theorem guard_snapshot (e : Encoding) (c : Invocation) (h : ServiceTraceRefinesProposal e c)
    (g : Guard) (hg : g ∈ c.request.guards) :
    ∃ r : Image, c.peer.entries (e.entry g.key) = some (r.record e) ∧
      0 ≤ r.version ∧ r.version.toNat = g.expected.toNat := by
  rcases List.mem_iff_getElem?.mp hg with ⟨i, hi⟩
  rcases checked_provenance e c h i g hi with ⟨o,r,v,_,_,_,_,_,hvr,hve,hp⟩
  have he : r.version = g.expected := hvr.symm.trans hve
  exact ⟨r, hp, he ▸ (h.typed.guard_types g hg).2.2.1, congrArg Int.toNat he⟩

theorem target_snapshot (e : Encoding) (c : Invocation) (h : ServiceTraceRefinesProposal e c)
    (hm : c.request.mode = .update) :
    ∃ r : Image, c.peer.entries (e.entry c.request.target) = some (r.record e) ∧
      0 ≤ r.version ∧ r.version < maxLong ∧ c.trace.image.version = r.version+1 := by
  rcases h.post.target_update hm with ⟨o,r,ho,hk,hr,h0,hmax,hv,_,_⟩
  have hs := h.snapshot.answers 0 o ho
  exact ⟨r, by simpa [hk,hr] using hs, h0, hmax, hv⟩

theorem creation_snapshot (e : Encoding) (c : Invocation) (h : ServiceTraceRefinesProposal e c)
    (hm : c.request.mode = .create) :
    c.peer.entries (e.entry c.request.target) = none ∧ c.trace.image.version = 0 := by
  rcases h.post.target_create hm with ⟨o,ho,hk,hr,hv,_,_⟩
  exact ⟨by simpa [hk,hr] using h.snapshot.answers 0 o ho, hv⟩

theorem eligibility : EligibilitySpec := by
  intro e c h
  cases hm : c.request.mode with
  | create => simp [proposal, eligible, hm, (creation_snapshot e c h hm).1, call]
  | extra => simp [proposal, eligible, hm]
  | update =>
    apply (eligible_update_iff (proposal e c) hm).mpr
    constructor
    · rcases target_snapshot e c h hm with ⟨r,hr,h0,hmax,_⟩
      exact ⟨r.record e,hr,h0,hmax⟩
    · intro g hg
      rcases List.mem_map.mp hg with ⟨a,ha,rfl⟩
      rcases guard_snapshot e c h a ha with ⟨r,hr,h0,hv⟩
      exact ⟨r.record e,hr,h0,hv⟩

theorem write_image : WriteImageSpec := by
  intro e c h hm
  have ha := h.post.image_accumulator hm
  cases hmode : c.request.mode with
  | extra => exact False.elim (hm hmode)
  | create =>
    have hv := (creation_snapshot e c h hmode).2
    simp [Image.record, writtenRecord, proposal, hmode, hv, ha]
  | update =>
    rcases target_snapshot e c h hmode with ⟨r,hr,_,_,hv⟩
    simp [Image.record, writtenRecord, proposal, call, hmode, hv, ha, hr]

theorem requested_membership (e : Encoding) (q : Request) (k : Key) :
    k ∈ entryReads ⟨q.mode, call e q, ⟨fun _ => none, fun _ => 0, fun _ => 0⟩, 0, []⟩ ↔
      ∃ name ∈ requestedEntries q, e.entry name = k := by
  cases hm : q.mode with
  | create => simp [entryReads, requestedEntries, call, hm, eq_comm]
  | extra => simp [entryReads, requestedEntries, hm]
  | update =>
    simp only [entryReads, hm, call, List.map_map, List.mem_cons, List.mem_map,
      Function.comp_apply, requestedEntries, nonself]
    constructor
    · intro hk
      rcases hk with hk | ⟨g,hg,hk⟩
      · exact ⟨q.target, Or.inl rfl, hk.symm⟩
      · by_cases hs : g.key = q.target
        · exact ⟨q.target, Or.inl rfl, hs ▸ hk⟩
        · exact ⟨g.key, Or.inr ⟨g, List.mem_filter.mpr ⟨hg, by simpa using hs⟩, rfl⟩, hk⟩
    · rintro ⟨name, hn, hk⟩
      rcases hn with hn | ⟨g,hg,hname⟩
      · exact Or.inl (hk.symm.trans (congrArg e.entry hn))
      · exact Or.inr ⟨g, (List.mem_filter.mp hg).1, (congrArg e.entry hname).trans hk⟩

theorem read_coverage : ReadCoverageSpec := by
  intro e c h k
  have hm := requested_membership e c.request k
  change (k ∈ entryReads (proposal e c) ↔ _) at hm
  rw [← h.post.entry_sequence] at hm
  constructor
  · intro hk
    rcases hm.mp hk with ⟨name, hn, hk'⟩
    rcases List.mem_map.mp hn with ⟨o, ho, rfl⟩
    exact ⟨o,ho,hk'⟩
  · rintro ⟨o,ho,hk⟩
    exact hm.mpr ⟨o.requested, List.mem_map.mpr ⟨o,ho,rfl⟩, hk⟩

theorem physical_entry_revisions (e : Encoding) (c : Invocation)
    (h : ServiceTraceRefinesProposal e c) (s : Ledger) :
    (c.trace.entries.all (fun o => s.revision (e.entry o.requested) == o.revision) = true) ↔
      ∀ k ∈ entryReads (proposal e c), s.revision k = c.peer.revision k := by
  simp only [List.all_eq_true, beq_iff_eq]
  constructor
  · intro hv k hk
    rcases (read_coverage e c h k).mp hk with ⟨o,ho,hk⟩
    simpa [hk] using (hv o ho).trans (h.snapshot.entry_revisions o ho)
  · intro hv o ho
    rw [h.snapshot.entry_revisions o ho]
    exact hv _ ((read_coverage e c h _).mpr ⟨o,ho,rfl⟩)

theorem captured_extra_revisions (e : Encoding) (c : Invocation)
    (h : ServiceTraceRefinesProposal e c) :
    ∀ o ∈ capturedExtras e c.trace, o.2 = c.peer.extraRevision o.1 := by
  intro o ho
  rcases List.mem_append.mp ho with hp | ha
  · rcases List.mem_map.mp hp with ⟨p,hpMem,rfl⟩
    exact h.snapshot.parent_revisions p hpMem
  · rcases List.mem_map.mp ha with ⟨a,haMem,rfl⟩
    exact h.snapshot.auth_revisions a haMem

theorem extra_coverage (e : Encoding) (c : Invocation)
    (h : ServiceTraceRefinesProposal e c) (k : Key) :
    k ∈ extraReads (proposal e c) ↔ k ∈ (capturedExtras e c.trace).map Prod.fst := by
  cases hm : c.request.mode with
  | create | update => simp [extraReads, proposal, hm]
  | extra =>
    simp only [extraReads, proposal, hm, call, List.mem_cons]
    exact ⟨fun hk => hk.elim (fun hk => hk ▸ h.post.extra_primary_read hm) id, Or.inr⟩

theorem physical_extra_revisions (e : Encoding) (c : Invocation)
    (h : ServiceTraceRefinesProposal e c) (s : Ledger) :
    ((capturedExtras e c.trace).all (fun o => s.extraRevision o.1 == o.2) = true) ↔
      ∀ k ∈ extraReads (proposal e c), s.extraRevision k = c.peer.extraRevision k := by
  simp only [List.all_eq_true, beq_iff_eq]
  constructor
  · intro hv k hk
    rcases List.mem_map.mp ((extra_coverage e c h k).mp hk) with ⟨o,ho,hk⟩
    simpa [hk] using (hv o ho).trans (captured_extra_revisions e c h o ho)
  · intro hv o ho
    rw [captured_extra_revisions e c h o ho]
    exact hv _ ((extra_coverage e c h _).mpr (List.mem_map.mpr ⟨o,ho,rfl⟩))

theorem validation : ValidationSpec := by
  intro e c h s
  apply Bool.eq_iff_iff.mpr
  simp only [validate, Bool.and_eq_true, physical_entry_revisions e c h s,
    physical_extra_revisions e c h s, revisionsMatch, List.all_eq_true, beq_iff_eq]
  rfl

theorem ledger_ext (a b : Ledger) (he : ∀ k, a.entries k = b.entries k)
    (hr : ∀ k, a.revision k = b.revision k)
    (hx : ∀ k, a.extraRevision k = b.extraRevision k) : a = b := by
  have he' := funext he
  have hr' := funext hr
  have hx' := funext hx
  cases a; cases b
  simp_all

theorem apply_refinement : ApplySpec := by
  intro e c h s
  have hw := h.post.write_sequence
  apply ledger_ext
  · intro k
    cases hm : c.request.mode with
    | extra => simp [applyTrace, hw, hm, Atomic.apply, transaction, proposal, LedgerRefinement.apply]
    | create | update =>
      have hi := write_image e c h (by simp [hm])
      by_cases hk : k = e.entry c.request.target <;>
        simp [applyTrace, hw, hm, Atomic.apply, transaction, proposal, call, LedgerRefinement.apply,
          hk, hi]
      all_goals simp [Ne.symm hk]
  · intro k
    cases hm : c.request.mode <;>
      by_cases hk : k = e.entry c.request.target <;>
      simp [applyTrace, hw, hm, Atomic.apply, transaction, proposal, call, LedgerRefinement.apply,
        hk]
  · intro k
    rw [(Atomic.atomic_writes s (transaction e c) k).2.2]
    change (if k ∈ c.trace.extraWrites then s.extraRevision k + 1 else s.extraRevision k) = _
    have hp := h.post.extra_primary_write
    by_cases hx : k ∈ c.trace.extraWrites
    · simp [transaction, proposal, hx]
    · have hn : ¬ (c.request.mode = .extra ∧ k = e.entry c.request.target) := by
        rintro ⟨hm,hk⟩
        exact hx (hk ▸ hp hm)
      simp [transaction, proposal, call, hx, hn]

theorem acceptance_refinement (e : Encoding) (s : Ledger) (c : Invocation) (permit : Bool)
    (h : c.trace.returned = true → ServiceTraceRefinesProposal e c) :
    acceptedTrace e s c.trace permit = accepted s (proposal e c) (permit && c.trace.returned) := by
  cases hn : c.trace.returned with
  | false => simp [acceptedTrace, accepted, hn]
  | true => simp [acceptedTrace, accepted, hn, eligibility e c (h hn), validation e c (h hn) s]

theorem step_refinement : StepSpec := by
  intro e s c permit h
  unfold stepTrace Atomic.step
  change (if acceptedTrace e s c.trace permit then _ else s) =
    (if accepted s (proposal e c) (permit && c.trace.returned) then _ else s)
  rw [acceptance_refinement e s c permit h]
  split
  · have hn : c.trace.returned = true := by
      have ha := (acceptance_iff _ _ _).mp ‹_›
      exact (Bool.and_eq_true_iff.mp ha.1).2
    exact apply_refinement e c (h hn) s
  · rfl

theorem run_refinement : RunSpec := by
  intro e s cs permit h n
  induction n with
  | zero => rfl
  | succ n ih =>
    simp only [stateAt, Atomic.stateAt]
    rw [step_refinement e _ (cs n) (permit n) (h n), ih]
    rfl

theorem commit_refinement : CommitSpec := by
  intro e s cs permit h i
  unfold commits Atomic.commits
  rw [acceptance_refinement e _ (cs i) (permit i) (h i), run_refinement e s cs permit h i]
  rfl

theorem origins_transport (e : Encoding) (s : Ledger) (cs : Invocations) (permit : Nat → Bool)
    (h : PerCallContracts e cs) (hp : PlatformOrigins e s cs permit) :
    Atomic.Origins s (transactions e cs) (permissions cs permit) := by
  intro i
  rcases hp i with ⟨a,ha,hpeer⟩
  exact ⟨a,ha,hpeer.trans (run_refinement e s cs permit h a)⟩

theorem no_reset : NoResetSpec := by
  intro e s cs permit h hp i hi hm
  rw [run_refinement e s cs permit h i]
  exact Atomic.no_reset s (transactions e cs) (permissions cs permit)
    (origins_transport e s cs permit h hp) i ((commit_refinement e s cs permit h i).mp hi) hm

theorem revocation_refinement (e : Encoding) (R₀ : Cred → Prop) (effects : Effects)
    (s : Ledger) (cs : Invocations) (permit : Nat → Bool) (h : PerCallContracts e cs) (n : Nat) (x : Cred) :
    revokedAt e R₀ effects s cs permit n x ↔
      Atomic.revokedAt R₀ effects s (transactions e cs) (permissions cs permit) n x := by
  simp only [revokedAt, Atomic.revokedAt, commit_refinement e s cs permit h]
  rfl

theorem honest_transport (e : Encoding) (R₀ : Cred → Prop) (effects : Effects)
    (s : Ledger) (cs : Invocations) (permit : Nat → Bool) (check : Cred → Record → Prop)
    (hc : PerCallContracts e cs) (hf : CheckFidelity e R₀ effects s cs permit check)
    (chk use : Nat) (own other : Cred)
    (h : HonestCheck e s cs permit check effects chk use own other) :
    Atomic.HonestStep R₀ effects s (transactions e cs) (permissions cs permit) chk use own other := by
  constructor
  · rcases h.observed with ⟨r,hr,hcheck⟩
    intro hv
    exact hf chk other r hr hcheck ((revocation_refinement e R₀ effects s cs permit hc chk other).mpr hv)
  · intro g hg
    rcases List.mem_map.mp hg with ⟨a,ha,rfl⟩
    rcases h.pins a ha with ⟨r,hr,hpin⟩
    rw [← run_refinement e s cs permit hc chk]
    simpa [project,hr] using hpin
  · rcases h.guards_other with ⟨g,hg,hk⟩
    refine ⟨g.expected.toNat, List.mem_map.mpr ⟨g,hg,?_⟩⟩
    simp [hk]
  · exact h.update
  · exact eligibility e (cs use) (hc use h.returned)
  · exact ⟨h.update,h.own_target,h.intended_effect⟩

theorem safety : SafetySpec := by
  intro e R₀ effects s cs permit check chkA chkB iA iB xA xB hc hw hp hf hA hB hid ha hb hboth
  exact Atomic.no_bifurcation R₀ effects s (transactions e cs) (permissions cs permit)
    chkA chkB iA iB xA xB hw (origins_transport e s cs permit hc hp)
    (honest_transport e R₀ effects s cs permit check hc hf chkA iA xA xB hA)
    (honest_transport e R₀ effects s cs permit check hc hf chkB iB xB xA hB)
    hid ha hb ⟨(commit_refinement e s cs permit hc iA).mp hboth.1,
      (commit_refinement e s cs permit hc iB).mp hboth.2⟩

theorem end_to_end : EndToEndSpec := by
  intro e R₀ effects s cs permit check authentic realizes chkA chkB iA iB xA xB hc hw hp hf hA hB hid ha hb
  exact ⟨(hf.effects_exact iA xA (hf.authenticated iA hA.returned)).mp hA.intended_effect,
    (hf.effects_exact iB xB (hf.authenticated iB hB.returned)).mp hB.intended_effect,
    safety e R₀ effects s cs permit check chkA chkB iA iB xA xB hc hw hp hf.checks hA hB hid ha hb⟩

theorem filter_take_count_step (gs : List Guard) (target : String) (i : Nat) (g : Guard)
    (h : gs[i]? = some g) :
    ((gs.take (i+1)).filter (fun a => a.key != target)).length =
      ((gs.take i).filter (fun a => a.key != target)).length +
        (if g.key = target then 0 else 1) := by
  induction gs generalizing i with
  | nil => simp only [List.getElem?_nil] at h; cases h
  | cons a gs ih =>
    cases i with
    | zero =>
      have ha : a = g := Option.some.inj h
      subst a
      by_cases hg : g.key = target <;> simp [hg]
    | succ i =>
      have hi : gs[i]? = some g := h
      have hrec := ih i hi
      by_cases ha : a.key = target
      · simpa [List.take_succ_cons, List.filter_cons,ha] using hrec
      · simpa [List.take_succ_cons, List.filter_cons,ha,Nat.add_assoc,Nat.add_comm,Nat.add_left_comm] using hrec

theorem nonself_count_recurrence (q : Request) (i : Nat) (g : Guard)
    (h : q.guards[i]? = some g) :
    ((q.guards.take (i+1)).filter (fun a => a.key != q.target)).length =
      ((q.guards.take i).filter (fun a => a.key != q.target)).length +
        (if g.key = q.target then 0 else 1) :=
  filter_take_count_step q.guards q.target i g h

theorem selfguard_ordinal (q : Request) (i : Nat) (g : Guard) (h : g.key = q.target) :
    ordinal q i g = 0 := by simp [ordinal,h]

theorem jml_absolute_answer_ordinal (e : Encoding) (c : Invocation)
    (h : ServiceTraceRefinesProposal e c) (before : List EntryObservation)
    (i : Nat) (g : Guard) (hi : c.request.guards[i]? = some g) :
    ∃ o r v, (before ++ c.trace.entries)[before.length + ordinal c.request i g]? = some o ∧
      o.requested = g.key ∧ o.answer = some r ∧ c.trace.checked[i]? = some v ∧
      v.version = r.version ∧ v.version = g.expected ∧
      c.peer.entries (e.entry g.key) = some (r.record e) := by
  rcases checked_provenance e c h i g hi with ⟨o,r,v,ho,hk,hr,hv,_,hvr,hve,hp⟩
  refine ⟨o,r,v,?_,hk,hr,hv,hvr,hve,hp⟩
  rw [List.getElem?_append_right (by omega)]
  simpa using ho

theorem honest_present_record_transport (e : Encoding) (s : Ledger) (cs : Invocations)
    (permit : Nat → Bool) (hc : PerCallContracts e cs) (n : Nat) (k : Key) (r : Record)
    (h : (stateAt e s cs permit n).entries k = some r) :
    (Atomic.stateAt s (transactions e cs) (permissions cs permit) n).entries k = some r ∧
    project (Atomic.stateAt s (transactions e cs) (permissions cs permit) n) k = r.version.toNat := by
  have hp : (Atomic.stateAt s (transactions e cs) (permissions cs permit) n).entries k = some r := by
    rw [← run_refinement e s cs permit hc n]
    exact h
  exact ⟨hp,by simp [project,hp]⟩

end ServiceTraceBridge
