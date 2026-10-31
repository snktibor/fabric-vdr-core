import AtomicModel

namespace ServiceTraceBridge
open Reconstruction LedgerRefinement

/- String represents Java String *value*, on the losslessly decoded UTF-16 domain.
   Surrogate/codec/runtime correspondence is an external boundary. -/
def javaTrim (s : String) : String :=
  String.mk (((s.toList.dropWhile (fun c => c.toNat ≤ 32)).reverse.dropWhile
    (fun c => c.toNat ≤ 32)).reverse)

structure Encoding where
  entry : String → Key
  parent : String → Key
  payload : String → Nat
  entry_injective : ∀ a b, entry a = entry b → a = b
  parent_injective : ∀ a b, parent a = parent b → a = b
  payload_injective : ∀ a b, payload a = payload b → a = b

structure Guard where
  key : String
  expected : Int
  deriving DecidableEq, Repr

structure Request where
  id : Nat
  mode : Mode
  rawTarget : String
  rawAccumulator : String
  target : String
  accumulator : String
  signer : String
  timestamp : String
  guards : List Guard
  guarded : Bool
  deriving DecidableEq, Repr

structure TypedRequest (q : Request) : Prop where
  target_normalized : q.target = javaTrim q.rawTarget
  accumulator_normalized : q.accumulator = javaTrim q.rawAccumulator
  target_nonempty : q.target ≠ ""
  accumulator_nonempty : q.accumulator ≠ ""
  guard_types : ∀ g ∈ q.guards, javaTrim g.key = g.key ∧ g.key ≠ "" ∧
    0 ≤ g.expected ∧ g.expected ≤ maxLong
  plain : q.guarded = false → q.guards = []
  guarded_mode : q.guarded = true → q.mode = .update ∧ 1 ≤ q.guards.length ∧ q.guards.length ≤ 16
  nonguarded_modes : q.mode ≠ .update → q.guards = []

structure Image where
  id : String
  accumulator : String
  version : Int
  created : String
  updated : String
  deriving DecidableEq, Repr

def Image.record (e : Encoding) (r : Image) : Record := ⟨r.version, e.payload r.accumulator⟩

structure EntryObservation where
  requested : String
  answer : Option Image
  revision : Nat
  deriving DecidableEq, Repr

structure ParentObservation where
  requested : String
  owner : Option String
  revision : Nat
  deriving DecidableEq, Repr

structure ExtraObservation where
  key : Key
  value : Option String
  revision : Nat
  deriving DecidableEq, Repr

structure EntryWrite where
  requested : String
  image : Image
  deriving DecidableEq, Repr

structure Checked where
  requested : String
  version : Int
  deriving DecidableEq, Repr

structure Trace where
  returned : Bool
  entries : List EntryObservation
  parents : List ParentObservation
  authReads : List ExtraObservation
  writes : List EntryWrite
  extraWrites : List Key
  checked : List Checked
  image : Image

structure Invocation where
  request : Request
  trace : Trace
  peer : Ledger

abbrev Invocations := Nat → Invocation

def nonself (q : Request) : List Guard := q.guards.filter (fun g => g.key != q.target)
def ordinal (q : Request) (i : Nat) (g : Guard) : Nat :=
  if g.key = q.target then 0 else
    1 + ((q.guards.take i).filter (fun h => h.key != q.target)).length

def requestedEntries (q : Request) : List String :=
  match q.mode with
  | .create => [q.target]
  | .update => q.target :: (nonself q).map Guard.key
  | .extra => []

def capturedExtras (e : Encoding) (t : Trace) : List (Key × Nat) :=
  t.parents.map (fun p => (e.parent p.requested, p.revision)) ++
  t.authReads.map (fun o => (o.key, o.revision))

def call (e : Encoding) (q : Request) : GuardedCall :=
  ⟨q.id, e.entry q.target, q.guards.map (fun g => (e.entry g.key, g.expected.toNat))⟩

def proposal (e : Encoding) (c : Invocation) : Proposal :=
  ⟨c.request.mode, call e c.request, c.peer, e.payload c.request.accumulator,
    (capturedExtras e c.trace).map Prod.fst⟩

def transaction (e : Encoding) (c : Invocation) : Atomic.Transaction :=
  ⟨proposal e c, c.trace.extraWrites⟩

def transactions (e : Encoding) (cs : Invocations) : Atomic.Transactions :=
  fun i => transaction e (cs i)

def permissions (cs : Invocations) (permit : Nat → Bool) : Nat → Bool :=
  fun i => permit i && (cs i).trace.returned

/- The immutable answer at ordinal i is tied to that ordinal's requested key.
   The relation imposes no guard/version comparison and no desired eligibility. -/
structure SnapshotRelation (e : Encoding) (c : Invocation) : Prop where
  answers : ∀ (i : Nat) (o : EntryObservation), c.trace.entries[i]? = some o →
    c.peer.entries (e.entry o.requested) = o.answer.map (Image.record e)
  entry_revisions : ∀ o ∈ c.trace.entries,
    o.revision = c.peer.revision (e.entry o.requested)
  parent_revisions : ∀ p ∈ c.trace.parents,
    p.revision = c.peer.extraRevision (e.parent p.requested)
  auth_revisions : ∀ o ∈ c.trace.authReads,
    o.revision = c.peer.extraRevision o.key

/- Granular postconditions of normal-return Java calls. This is a named,
   externally checked Hoare boundary, not verified Java execution in Lean. -/
structure NormalPost (e : Encoding) (c : Invocation) : Prop where
  entry_sequence : c.trace.entries.map EntryObservation.requested = requestedEntries c.request
  parent_sequence : c.trace.parents.map ParentObservation.requested =
    if c.request.mode = .extra then [] else [c.request.target]
  owner : ∀ p ∈ c.trace.parents, p.owner = some c.request.signer ∧ javaTrim c.request.signer ≠ ""
  checked_length : c.trace.checked.length = c.request.guards.length
  checked_source : ∀ i g, c.request.guards[i]? = some g →
    ∃ o r v, c.trace.entries[ordinal c.request i g]? = some o ∧
      o.requested = g.key ∧ o.answer = some r ∧ c.trace.checked[i]? = some v ∧
      v.requested = g.key ∧ v.version = r.version ∧ v.version = g.expected
  target_update : c.request.mode = .update → ∃ o r,
    c.trace.entries[0]? = some o ∧ o.requested = c.request.target ∧ o.answer = some r ∧
      0 ≤ r.version ∧ r.version < maxLong ∧
      c.trace.image.version = r.version + 1 ∧ c.trace.image.id = r.id ∧
      c.trace.image.created = r.created
  target_create : c.request.mode = .create → ∃ o,
    c.trace.entries[0]? = some o ∧ o.requested = c.request.target ∧ o.answer = none ∧
      c.trace.image.version = 0 ∧ c.trace.image.id = c.request.target ∧
      c.trace.image.created = c.request.timestamp
  image_accumulator : c.request.mode ≠ .extra → c.trace.image.accumulator = c.request.accumulator
  image_time : c.request.mode ≠ .extra → c.trace.image.updated = c.request.timestamp
  write_sequence : c.trace.writes =
    if c.request.mode = .extra then [] else [⟨c.request.target, c.trace.image⟩]
  extra_primary_read : c.request.mode = .extra →
    e.entry c.request.target ∈ (capturedExtras e c.trace).map Prod.fst
  extra_primary_write : c.request.mode = .extra → e.entry c.request.target ∈ c.trace.extraWrites

/- Auth caller observations and nonce writes are explicit, in the same trace.
   Content/cryptographic correctness is separate from the revision-only ledger. -/
structure AuthContract (e : Encoding) (c : Invocation) where
  didKey : Key
  nonceKey : Key
  didImage : String
  nonceImage : String
  did_read : ∃ o ∈ c.trace.authReads, o.key = didKey ∧ o.value = some didImage
  nonce_absent_read : ∃ o ∈ c.trace.authReads, o.key = nonceKey ∧ o.value = none
  nonce_written : nonceKey ∈ c.trace.extraWrites
  separate_parent : ∀ name, nonceKey ≠ e.parent name

structure ServiceTraceRefinesProposal (e : Encoding) (c : Invocation) : Prop where
  typed : TypedRequest c.request
  post : NormalPost e c
  snapshot : SnapshotRelation e c

/- No eligibility/abstract-validation check occurs in this interpreter. It uses
   only the normal-return bit, permission, captured revisions, and write images. -/
def validate (e : Encoding) (s : Ledger) (t : Trace) : Bool :=
  t.entries.all (fun o => s.revision (e.entry o.requested) == o.revision) &&
  (capturedExtras e t).all (fun o => s.extraRevision o.1 == o.2)

def acceptedTrace (e : Encoding) (s : Ledger) (t : Trace) (permit : Bool) : Bool :=
  permit && t.returned && validate e s t

def applyTrace (e : Encoding) (s : Ledger) (t : Trace) : Ledger :=
  { entries := fun k => match t.writes.find? (fun w => e.entry w.requested == k) with
      | some w => some (w.image.record e)
      | none => s.entries k
    revision := fun k =>
      if (t.writes.map (fun w => e.entry w.requested)).contains k then s.revision k + 1 else s.revision k
    extraRevision := fun k => if k ∈ t.extraWrites then s.extraRevision k + 1 else s.extraRevision k }

def stepTrace (e : Encoding) (s : Ledger) (c : Invocation) (permit : Bool) : Ledger :=
  if acceptedTrace e s c.trace permit then applyTrace e s c.trace else s

def stateAt (e : Encoding) (s : Ledger) (cs : Invocations) (permit : Nat → Bool) : Nat → Ledger
  | 0 => s
  | n+1 => stepTrace e (stateAt e s cs permit n) (cs n) (permit n)

def commits (e : Encoding) (s : Ledger) (cs : Invocations) (permit : Nat → Bool) (i : Nat) : Prop :=
  acceptedTrace e (stateAt e s cs permit i) (cs i).trace (permit i) = true

def PerCallContracts (e : Encoding) (cs : Invocations) : Prop :=
  ∀ i, (cs i).trace.returned = true → ServiceTraceRefinesProposal e (cs i)

def PlatformOrigins (e : Encoding) (s : Ledger) (cs : Invocations) (permit : Nat → Bool) : Prop :=
  ∀ i, ∃ a, a ≤ i ∧ (cs i).peer = stateAt e s cs permit a

def revokedAt (e : Encoding) (R₀ : Cred → Prop) (effects : Effects)
    (s : Ledger) (cs : Invocations) (permit : Nat → Bool) (n : Nat) (x : Cred) : Prop :=
  R₀ x ∨ ∃ i, i < n ∧ commits e s cs permit i ∧ concreteRevokes effects (proposal e (cs i)) x

/- The client supplies an actual present record, including accumulator and version.
   Pin provenance for every guard uses the same declared check prefix. -/
structure HonestCheck (e : Encoding) (s : Ledger) (cs : Invocations) (permit : Nat → Bool)
    (check : Cred → Record → Prop) (effects : Effects) (chk use : Nat) (own other : Cred) : Prop where
  observed : ∃ r, (stateAt e s cs permit chk).entries other.1 = some r ∧ check other r
  pins : ∀ g ∈ (cs use).request.guards, ∃ r,
    (stateAt e s cs permit chk).entries (e.entry g.key) = some r ∧ g.expected.toNat = r.version.toNat
  guards_other : ∃ g ∈ (cs use).request.guards, e.entry g.key = other.1
  update : (cs use).request.mode = .update
  returned : (cs use).trace.returned = true
  own_target : own.1 = e.entry (cs use).request.target
  intended_effect : effects (cs use).request.id own

def CheckFidelity (e : Encoding) (R₀ : Cred → Prop) (effects : Effects)
    (s : Ledger) (cs : Invocations) (permit : Nat → Bool) (check : Cred → Record → Prop) : Prop :=
  ∀ n x r, (stateAt e s cs permit n).entries x.1 = some r → check x r →
    ¬ revokedAt e R₀ effects s cs permit n x

/- This boundary exports actual payload effects only for authenticated calls.
   Ownership alone does not establish accumulator semantics. -/
structure AuthenticatedFidelity (e : Encoding) (R₀ : Cred → Prop) (effects : Effects)
    (s : Ledger) (cs : Invocations) (permit : Nat → Bool) (check : Cred → Record → Prop)
    (authentic : Nat → String → Prop) (realizes : Nat → Cred → Prop) : Prop where
  authenticated : ∀ i, (cs i).trace.returned = true → authentic (cs i).request.id (cs i).request.signer
  effects_exact : ∀ i x, authentic (cs i).request.id (cs i).request.signer →
    (effects (cs i).request.id x ↔ realizes (cs i).request.id x)
  checks : CheckFidelity e R₀ effects s cs permit check

end ServiceTraceBridge
