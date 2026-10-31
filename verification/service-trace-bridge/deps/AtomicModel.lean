import LedgerModel

namespace LedgerRefinement.Atomic
open Reconstruction

structure Transaction where
  proposal : Proposal
  extraWrites : List Key

abbrev Transactions := Nat → Transaction

def apply (s : Ledger) (t : Transaction) : Ledger :=
  let base := LedgerRefinement.apply s t.proposal
  { base with extraRevision := fun k =>
      if k ∈ t.extraWrites then s.extraRevision k + 1 else base.extraRevision k }

def step (s : Ledger) (t : Transaction) (permit : Bool) : Ledger :=
  if accepted s t.proposal permit then apply s t else s

def stateAt (s : Ledger) (ts : Transactions) (permit : Nat → Bool) : Nat → Ledger
  | 0 => s
  | n + 1 => step (stateAt s ts permit n) (ts n) (permit n)

def commits (s : Ledger) (ts : Transactions) (permit : Nat → Bool) (i : Nat) : Prop :=
  accepted (stateAt s ts permit i) (ts i).proposal (permit i) = true

def Origins (s : Ledger) (ts : Transactions) (permit : Nat → Bool) : Prop :=
  ∀ i, ∃ a, a ≤ i ∧ (ts i).proposal.peer = stateAt s ts permit a

def abstractStream (ts : Transactions) : Stream := fun i => abstractTx (ts i).proposal

def allowance (s : Ledger) (ts : Transactions) (permit : Nat → Bool) : Nat → Bool :=
  fun i => accepted (stateAt s ts permit i) (ts i).proposal (permit i)

def revokedAt (R₀ : Cred → Prop) (effects : Effects)
    (s : Ledger) (ts : Transactions) (permit : Nat → Bool) (n : Nat) (x : Cred) : Prop :=
  R₀ x ∨ ∃ i, i < n ∧ commits s ts permit i ∧ concreteRevokes effects (ts i).proposal x

structure HonestStep (R₀ : Cred → Prop) (effects : Effects)
    (s : Ledger) (ts : Transactions) (permit : Nat → Bool) (chk use : Nat)
    (own other : Cred) : Prop where
  checked : ¬ revokedAt R₀ effects s ts permit chk other
  pinned : ∀ g ∈ (ts use).proposal.call.guards,
    g.2 = project (stateAt s ts permit chk) g.1
  guardsOther : ∃ v, (other.1, v) ∈ (ts use).proposal.call.guards
  update : (ts use).proposal.mode = .update
  endorsed : eligible (ts use).proposal = true
  revokesOwn : concreteRevokes effects (ts use).proposal own

end LedgerRefinement.Atomic
