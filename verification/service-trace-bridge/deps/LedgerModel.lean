import SkipModel

namespace LedgerRefinement
open Reconstruction

def maxLong : Int := 9223372036854775807

structure Record where
  version : Int
  accumulator : Nat
  deriving DecidableEq, Repr

structure Ledger where
  entries : Key → Option Record
  revision : Key → Nat
  extraRevision : Key → Nat

def WellFormed (s : Ledger) : Prop :=
  ∀ k r, s.entries k = some r → 0 ≤ r.version ∧ r.version ≤ maxLong

def project (s : Ledger) : State :=
  fun k => ((s.entries k).getD ⟨0, 0⟩).version.toNat

def nextVersion (v : Int) : Option Int :=
  if 0 ≤ v ∧ v < maxLong then some (v + 1) else none

inductive Mode where
  | create | update | extra
  deriving DecidableEq, Repr

structure Proposal where
  mode : Mode
  call : GuardedCall
  peer : Ledger
  accumulator : Nat
  extraReads : List Key

abbrev Proposals := Nat → Proposal

def guardMatches (peer : Ledger) (g : Key × Nat) : Bool :=
  match peer.entries g.1 with
  | none => false
  | some r => decide (0 ≤ r.version ∧ r.version.toNat = g.2)

def eligible (p : Proposal) : Bool :=
  match p.mode with
  | .create => (p.peer.entries p.call.target).isNone
  | .update => match p.peer.entries p.call.target with
    | none => false
    | some r => (nextVersion r.version).isSome && p.call.guards.all (guardMatches p.peer)
  | .extra => true

def entryReads (p : Proposal) : List Key :=
  match p.mode with
  | .create => [p.call.target]
  | .update => p.call.target :: p.call.guards.map Prod.fst
  | .extra => []

def extraReads (p : Proposal) : List Key :=
  match p.mode with
  | .extra => p.call.target :: p.extraReads
  | _ => p.extraReads

def revisionsMatch (s : Ledger) (p : Proposal) : Bool :=
  (entryReads p).all (fun k => s.revision k == p.peer.revision k) &&
  (extraReads p).all (fun k => s.extraRevision k == p.peer.extraRevision k)

def accepted (s : Ledger) (p : Proposal) (permit : Bool) : Bool :=
  permit && eligible p && revisionsMatch s p

def writtenRecord (p : Proposal) : Record :=
  ⟨if p.mode = .create then 0 else
      ((p.peer.entries p.call.target).getD ⟨0, 0⟩).version + 1,
    p.accumulator⟩

def apply (s : Ledger) (p : Proposal) : Ledger :=
  match p.mode with
  | .extra => { s with extraRevision := fun k =>
      if k = p.call.target then s.extraRevision k + 1 else s.extraRevision k }
  | _ => { s with
      entries := fun k => if k = p.call.target then some (writtenRecord p) else s.entries k
      revision := fun k => if k = p.call.target then s.revision k + 1 else s.revision k }

def step (s : Ledger) (p : Proposal) (permit : Bool) : Ledger :=
  if accepted s p permit then apply s p else s

def stateAt (s : Ledger) (ps : Proposals) (permit : Nat → Bool) : Nat → Ledger
  | 0 => s
  | n + 1 => step (stateAt s ps permit n) (ps n) (permit n)

def commits (s : Ledger) (ps : Proposals) (permit : Nat → Bool) (i : Nat) : Prop :=
  accepted (stateAt s ps permit i) (ps i) (permit i) = true

def Origins (s : Ledger) (ps : Proposals) (permit : Nat → Bool) : Prop :=
  ∀ i, ∃ a, a ≤ i ∧ (ps i).peer = stateAt s ps permit a

def abstractTx (p : Proposal) : Tx :=
  match p.mode with
  | .update => endorsedTx (project p.peer) p.call
  | _ => ⟨p.call.id, [], []⟩

def abstractStream (ps : Proposals) : Stream := fun i => abstractTx (ps i)

/- This filter is computed entirely by the concrete run, before any abstract execution.
   Rejection includes failed endorsement, revision conflicts and arbitrary external rejection. -/
def allowance (s : Ledger) (ps : Proposals) (permit : Nat → Bool) : Nat → Bool :=
  fun i => accepted (stateAt s ps permit i) (ps i) (permit i)

abbrev Effects := Nat → Cred → Prop

def concreteRevokes (effects : Effects) (p : Proposal) (x : Cred) : Prop :=
  p.mode = .update ∧ x.1 = p.call.target ∧ effects p.call.id x

def abstractRevokes (effects : Effects) : Revocations :=
  fun t x => x.1 ∈ t.writes ∧ effects t.id x

def revokedAt (R₀ : Cred → Prop) (effects : Effects)
    (s : Ledger) (ps : Proposals) (permit : Nat → Bool) (n : Nat) (x : Cred) : Prop :=
  R₀ x ∨ ∃ i, i < n ∧ commits s ps permit i ∧ concreteRevokes effects (ps i) x

structure HonestStep (R₀ : Cred → Prop) (effects : Effects)
    (s : Ledger) (ps : Proposals) (permit : Nat → Bool) (chk use : Nat)
    (own other : Cred) : Prop where
  checked : ¬ revokedAt R₀ effects s ps permit chk other
  pinned : ∀ g ∈ (ps use).call.guards,
    g.2 = project (stateAt s ps permit chk) g.1
  guardsOther : ∃ v, (other.1, v) ∈ (ps use).call.guards
  update : (ps use).mode = .update
  endorsed : eligible (ps use) = true
  revokesOwn : concreteRevokes effects (ps use) own

end LedgerRefinement
