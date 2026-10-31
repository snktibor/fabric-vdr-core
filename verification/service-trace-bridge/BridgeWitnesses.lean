import BridgeProofs

namespace ServiceTraceBridge.Witnesses
open Reconstruction LedgerRefinement

def oldT : Image := ⟨"T", "800000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000", 3, "created", "old-time"⟩
def oldA : Image := ⟨"A", "802222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222", 2, "created", "old-time"⟩
def oldB : Image := ⟨"B", "807777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777", 7, "created", "old-time"⟩
def newT : Image := ⟨"T", "801111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111", 4, "created", "now"⟩
def tObs : EntryObservation := ⟨"T", some oldT, 100⟩
def aObs : EntryObservation := ⟨"A", some oldA, 100⟩
def bObs : EntryObservation := ⟨"B", some oldB, 100⟩

def request : Request :=
  ⟨17, .update, " \tT \n", " 801111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111 ", "T", "801111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111", "did:issuer", "now",
    [⟨"T",3⟩,⟨"A",2⟩,⟨"B",7⟩], true⟩

def peer (e : Encoding) : Ledger :=
  ⟨fun k => if k = e.entry "T" then some (oldT.record e)
    else if k = e.entry "A" then some (oldA.record e)
    else if k = e.entry "B" then some (oldB.record e) else none,
   fun _ => 100, fun _ => 50⟩

def trace : Trace :=
  ⟨true, [tObs,aObs,bObs], [⟨"T",some "did:issuer",50⟩],
    [⟨90,some "DID",50⟩,⟨91,none,50⟩], [⟨"T",newT⟩], [91],
    [⟨"T",3⟩,⟨"A",2⟩,⟨"B",7⟩], newT⟩

def invocation (e : Encoding) : Invocation := ⟨request,trace,peer e⟩

theorem padded_normalization : javaTrim " \tT \n" = "T" ∧ javaTrim " 801111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111 " = "801111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111" := by decide

theorem typed_request : TypedRequest request := by
  constructor
  · decide
  · decide
  · decide
  · decide
  · intro g hg
    simp only [request, List.mem_cons, List.not_mem_nil, or_false] at hg
    rcases hg with rfl | rfl | rfl <;> decide
  · decide
  · decide
  · decide

theorem normal_post (e : Encoding) : NormalPost e (invocation e) := by
  constructor
  · dsimp only [invocation]; decide
  · dsimp only [invocation]; decide
  · intro p hp
    have hp' : p = ⟨"T",some "did:issuer",50⟩ := by simpa [invocation,trace] using hp
    subst p
    dsimp only [invocation]; decide
  · rfl
  · intro i g hi
    change request.guards[i]? = some g at hi
    cases i with
    | zero =>
      have hg : g = ⟨"T",3⟩ := by simpa [request] using hi.symm
      subst g
      exact ⟨tObs,oldT,⟨"T",3⟩,by dsimp only [invocation]; decide⟩
    | succ i => cases i with
      | zero =>
        have hg : g = ⟨"A",2⟩ := by simpa [request] using hi.symm
        subst g
        exact ⟨aObs,oldA,⟨"A",2⟩,by dsimp only [invocation]; decide⟩
      | succ i => cases i with
        | zero =>
          have hg : g = ⟨"B",7⟩ := by simpa [request] using hi.symm
          subst g
          exact ⟨bObs,oldB,⟨"B",7⟩,by dsimp only [invocation]; decide⟩
        | succ i => simp [request] at hi
  · intro _
    exact ⟨tObs,oldT,by dsimp only [invocation]; decide⟩
  · intro h
    cases h
  · intro _; rfl
  · intro _; rfl
  · rfl
  · intro h; cases h
  · intro h; cases h

theorem encoded_ne (e : Encoding) (a b : String) (h : a ≠ b) : e.entry a ≠ e.entry b := by
  intro he
  exact h (e.entry_injective a b he)

theorem snapshot (e : Encoding) : SnapshotRelation e (invocation e) := by
  have hat := encoded_ne e "A" "T" (by decide)
  have hbt := encoded_ne e "B" "T" (by decide)
  have hba := encoded_ne e "B" "A" (by decide)
  constructor
  · intro i o ho
    change [tObs,aObs,bObs][i]? = some o at ho
    cases i with
    | zero =>
      have ho' : o = tObs := by simpa using ho.symm
      subst o
      simp [invocation,tObs,peer]
    | succ i => cases i with
      | zero =>
        have ho' : o = aObs := by simpa using ho.symm
        subst o
        simp [invocation,aObs,peer,hat]
      | succ i => cases i with
        | zero =>
          have ho' : o = bObs := by simpa using ho.symm
          subst o
          simp [invocation,bObs,peer,hbt,hba]
        | succ i => simp at ho
  · intro o ho
    have ho' : o = tObs ∨ o = aObs ∨ o = bObs := by simpa [invocation,trace] using ho
    rcases ho' with rfl | rfl | rfl <;> rfl
  · intro p hp
    have hp' : p = ⟨"T",some "did:issuer",50⟩ := by simpa [invocation,trace] using hp
    subst p; rfl
  · intro o ho
    have ho' : o = ⟨90,some "DID",50⟩ ∨ o = ⟨91,none,50⟩ := by simpa [invocation,trace] using ho
    rcases ho' with rfl | rfl <;> rfl

theorem service_contract (e : Encoding) : ServiceTraceRefinesProposal e (invocation e) :=
  ⟨typed_request,normal_post e,snapshot e⟩

theorem heterogeneous_versions_and_self_reuse :
    request.guards.map Guard.expected = [3,2,7] ∧
    trace.entries.map EntryObservation.requested = ["T","A","B"] ∧
    trace.checked.map Checked.version = [3,2,7] ∧
    ordinal request 0 ⟨"T",3⟩ = 0 ∧ trace.image.version = 4 := by decide

theorem concrete_acceptance (e : Encoding) : acceptedTrace e (peer e) trace true = true := by rfl

theorem simultaneous_entry_and_nonce (e : Encoding) :
    (stepTrace e (peer e) (invocation e) true).entries (e.entry "T") = some (newT.record e) ∧
    (stepTrace e (peer e) (invocation e) true).revision (e.entry "T") = 101 ∧
    (stepTrace e (peer e) (invocation e) true).extraRevision 91 = 51 := by
  simp only [stepTrace, show (invocation e).trace = trace from rfl, concrete_acceptance e, if_true]
  simp [applyTrace, trace, peer]

theorem present_accumulator_and_version (e : Encoding) :
    (peer e).entries (e.entry "A") = some ⟨2,e.payload "802222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222"⟩ ∧
    (peer e).entries (e.entry "B") = some ⟨7,e.payload "807777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777"⟩ := by
  simp [peer, encoded_ne e "A" "T" (by decide), encoded_ne e "B" "T" (by decide),
    encoded_ne e "B" "A" (by decide), oldA,oldB,Image.record]

def createRequest : Request :=
  ⟨18,.create," C "," 80aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa ","C","80aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","did:issuer","now",[],false⟩
def genesis : Image := ⟨"C","80aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",0,"now","now"⟩
def absent : EntryObservation := ⟨"C",none,0⟩
def createPeer : Ledger := ⟨fun _ => none, fun _ => 0, fun _ => 0⟩
def createTrace : Trace :=
  ⟨true,[absent],[⟨"C",some "did:issuer",0⟩],[],[⟨"C",genesis⟩],[],[],genesis⟩
def creation : Invocation := ⟨createRequest,createTrace,createPeer⟩

theorem creation_contract (e : Encoding) : ServiceTraceRefinesProposal e creation := by
  constructor
  · constructor
    · decide
    · decide
    · decide
    · decide
    · simp [creation,createRequest]
    · decide
    · decide
    · decide
  · constructor
    · rfl
    · rfl
    · intro p hp
      have hp' : p = ⟨"C",some "did:issuer",0⟩ := by simpa [creation,createTrace] using hp
      subst p; decide
    · rfl
    · intro i g hi; simp [creation,createRequest] at hi
    · intro h; cases h
    · intro _; exact ⟨absent,by decide⟩
    · intro _; rfl
    · intro _; rfl
    · rfl
    · intro h; cases h
    · intro h; cases h
  · constructor
    · intro i o ho
      cases i with
      | zero =>
        have ho' : o = absent := by simpa [creation,createTrace] using ho.symm
        subst o; rfl
      | succ i =>
        change ([] : List EntryObservation)[i]? = some o at ho
        simp only [List.getElem?_nil] at ho
        cases ho
    · intro o ho
      have ho' : o = absent := by simpa [creation,createTrace] using ho
      subst o; rfl
    · intro p hp
      have hp' : p = ⟨"C",some "did:issuer",0⟩ := by simpa [creation,createTrace] using hp
      subst p; rfl
    · simp [creation,createTrace]

theorem creation_absence_zero_and_write (e : Encoding) :
    eligible (proposal e creation) = true ∧
    (stepTrace e createPeer creation true).entries (e.entry "C") = some ⟨0,e.payload "80aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"⟩ := by
  refine ⟨eligibility e creation (creation_contract e),?_⟩
  have ha : acceptedTrace e createPeer createTrace true = true := rfl
  simp only [stepTrace, show creation.trace = createTrace from rfl, ha, if_true]
  simp [applyTrace, createTrace, genesis, Image.record]

theorem auth_nonce_observations (e : Encoding) (separate : ∀ name, 91 ≠ e.parent name) :
    Nonempty (AuthContract e (invocation e)) := by
  refine ⟨⟨90,91,"DID","consumed",?_,?_,?_,separate⟩⟩
  · exact ⟨⟨90,some "DID",50⟩,by simp [invocation,trace]⟩
  · exact ⟨⟨91,none,50⟩,by simp [invocation,trace]⟩
  · simp [invocation,trace]

theorem nonce_conflict_rejects_matching_entries (e : Encoding) :
    let changed : Ledger := { peer e with extraRevision := fun k => if k = 91 then 51 else 50 }
    (∀ o ∈ trace.entries, changed.revision (e.entry o.requested) = o.revision) ∧
    acceptedTrace e changed trace true = false ∧
    stepTrace e changed (invocation e) true = changed := by
  dsimp only
  have hr : acceptedTrace e { peer e with extraRevision := fun k => if k = 91 then 51 else 50 }
      trace true = false := by
    dsimp [acceptedTrace,validate,capturedExtras,trace,peer,tObs,aObs,bObs]
    simp only [Bool.and_false, Bool.and_true]
  refine ⟨?_,hr,?_⟩
  · intro o ho
    have ho' : o = tObs ∨ o = aObs ∨ o = bObs := by simpa [trace] using ho
    rcases ho' with rfl | rfl | rfl <;> rfl
  · change (if acceptedTrace e _ trace true then _ else _) = _
    rw [hr]; rfl

theorem initial_wellformed (e : Encoding) : WellFormed (peer e) := by
  intro k r hr
  simp only [peer] at hr
  split at hr
  · cases Option.some.inj hr
    dsimp only [Image.record,oldT,oldA,oldB]; decide
  · split at hr
    · cases Option.some.inj hr
      dsimp only [Image.record,oldT,oldA,oldB]; decide
    · split at hr
      · cases Option.some.inj hr
        dsimp only [Image.record,oldT,oldA,oldB]; decide
      · cases hr

end ServiceTraceBridge.Witnesses
