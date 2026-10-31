import BridgeModel

namespace ServiceTraceBridge
open Reconstruction LedgerRefinement

def EligibilitySpec : Prop := ∀ e c, ServiceTraceRefinesProposal e c → eligible (proposal e c) = true

def WriteImageSpec : Prop := ∀ e c, ServiceTraceRefinesProposal e c → c.request.mode ≠ .extra →
  c.trace.image.record e = writtenRecord (proposal e c)

def ReadCoverageSpec : Prop := ∀ e c, ServiceTraceRefinesProposal e c → ∀ k,
  k ∈ entryReads (proposal e c) ↔ ∃ o ∈ c.trace.entries, e.entry o.requested = k

def CheckedProvenanceSpec : Prop := ∀ e c, ServiceTraceRefinesProposal e c → ∀ i g,
  c.request.guards[i]? = some g → ∃ o r v,
    c.trace.entries[ordinal c.request i g]? = some o ∧ o.requested = g.key ∧
    o.answer = some r ∧ c.trace.checked[i]? = some v ∧ v.requested = g.key ∧
    v.version = r.version ∧ v.version = g.expected ∧
    c.peer.entries (e.entry g.key) = some (r.record e)

def ValidationSpec : Prop := ∀ e c, ServiceTraceRefinesProposal e c → ∀ s,
  validate e s c.trace = revisionsMatch s (proposal e c)

def ApplySpec : Prop := ∀ e c, ServiceTraceRefinesProposal e c → ∀ s,
  applyTrace e s c.trace = Atomic.apply s (transaction e c)

def StepSpec : Prop := ∀ e s c permit,
  (c.trace.returned = true → ServiceTraceRefinesProposal e c) →
  stepTrace e s c permit = Atomic.step s (transaction e c) (permit && c.trace.returned)

def RunSpec : Prop := ∀ e s cs permit, PerCallContracts e cs → ∀ n,
  stateAt e s cs permit n = Atomic.stateAt s (transactions e cs) (permissions cs permit) n

def CommitSpec : Prop := ∀ e s cs permit, PerCallContracts e cs → ∀ i,
  commits e s cs permit i ↔ Atomic.commits s (transactions e cs) (permissions cs permit) i

def NoResetSpec : Prop := ∀ e s cs permit, PerCallContracts e cs → PlatformOrigins e s cs permit →
  ∀ i, commits e s cs permit i → (cs i).request.mode = .create →
    (stateAt e s cs permit i).entries (e.entry (cs i).request.target) = none

def SafetySpec : Prop := ∀ e R₀ effects s cs permit check chkA chkB iA iB xA xB,
  PerCallContracts e cs → WellFormed s → PlatformOrigins e s cs permit →
  CheckFidelity e R₀ effects s cs permit check →
  HonestCheck e s cs permit check effects chkA iA xA xB →
  HonestCheck e s cs permit check effects chkB iB xB xA →
  (cs iA).request.id ≠ (cs iB).request.id → chkA ≤ iA → chkB ≤ iB →
  ¬ (commits e s cs permit iA ∧ commits e s cs permit iB)

def EndToEndSpec : Prop := ∀ e R₀ effects s cs permit check authentic realizes chkA chkB iA iB xA xB,
  PerCallContracts e cs → WellFormed s → PlatformOrigins e s cs permit →
  AuthenticatedFidelity e R₀ effects s cs permit check authentic realizes →
  HonestCheck e s cs permit check effects chkA iA xA xB →
  HonestCheck e s cs permit check effects chkB iB xB xA →
  (cs iA).request.id ≠ (cs iB).request.id → chkA ≤ iA → chkB ≤ iB →
  realizes (cs iA).request.id xA ∧ realizes (cs iB).request.id xB ∧
    ¬ (commits e s cs permit iA ∧ commits e s cs permit iB)

end ServiceTraceBridge
