import Std

/- Independent reconstruction. Frozen semantic definitions, not original Argos source. -/
namespace Reconstruction

abbrev Key := Nat
abbrev State := Key → Nat
abbrev Cred := Key × Nat

structure Tx where
  id : Nat
  reads : List (Key × Nat)
  writes : List Key
  deriving DecidableEq, Repr

abbrev Stream := Nat → Tx

def readsOk (s : State) (t : Tx) : Bool :=
  t.reads.all (fun p => s p.1 == p.2)

def applyWrites (s : State) (t : Tx) : State :=
  fun k => if k ∈ t.writes then s k + 1 else s k

def step (s : State) (t : Tx) : State :=
  if readsOk s t then applyWrites s t else s

def stateAt (s : State) (ts : Stream) : Nat → State
  | 0 => s
  | n + 1 => step (stateAt s ts n) (ts n)

def commits (s : State) (ts : Stream) (i : Nat) : Prop :=
  readsOk (stateAt s ts i) (ts i) = true

def committed (s : State) (ts : Stream) (n i : Nat) : Prop :=
  i < n ∧ commits s ts i

structure GuardedCall where
  id : Nat
  target : Key
  guards : List (Key × Nat)
  deriving DecidableEq, Repr

def guardsOk (s : State) (c : GuardedCall) : Bool :=
  c.guards.all (fun p => s p.1 == p.2)

def endorsedTx (s : State) (c : GuardedCall) : Tx :=
  ⟨c.id, (c.target, s c.target) :: c.guards, [c.target]⟩

def endorse (s : State) (c : GuardedCall) : Option Tx :=
  if guardsOk s c then some (endorsedTx s c) else none

abbrev Revocations := Tx → Cred → Prop

def RevWF (rev : Revocations) : Prop :=
  ∀ t x, rev t x → x.1 ∈ t.writes

def revokedAt (R₀ : Cred → Prop) (rev : Revocations)
    (s : State) (ts : Stream) (n : Nat) (x : Cred) : Prop :=
  R₀ x ∨ ∃ i, committed s ts n i ∧ rev (ts i) x

structure GuardedStep (R₀ : Cred → Prop) (rev : Revocations)
    (s : State) (ts : Stream) (chk : Nat) (c : GuardedCall)
    (t : Tx) (own other : Cred) : Prop where
  checked : ¬ revokedAt R₀ rev s ts chk other
  pinned : ∀ p ∈ c.guards, p.2 = stateAt s ts chk p.1
  guardsOther : ∃ v, (other.1, v) ∈ c.guards
  endorsed : ∃ peer : State, endorse peer c = some t
  revokesOwn : rev t own

/- Frozen targets are propositions, so subsequent proofs cannot alter their types. -/
def FinalMonoSpec : Prop :=
  ∀ s ts a b, a ≤ b → ∀ k, stateAt s ts a k ≤ stateAt s ts b k

def StrictGrowthSpec : Prop :=
  ∀ s ts i k, commits s ts i → k ∈ (ts i).writes →
    stateAt s ts i k < stateAt s ts (i + 1) k

def StaleNeverCommitsSpec : Prop :=
  ∀ s ts a i k v, a ≤ i → (k, v) ∈ (ts i).reads →
    v < stateAt s ts a k → ¬ commits s ts i

def TasSpec : Prop :=
  ∀ peer c t, endorse peer c = some t → ∀ s,
    readsOk s t = true ↔
      s c.target = peer c.target ∧ ∀ p ∈ c.guards, s p.1 = p.2

def WindowClosedSpec : Prop :=
  ∀ s ts chk use peer c,
    chk ≤ use → endorse peer c = some (ts use) →
    (∀ p ∈ c.guards, p.2 = stateAt s ts chk p.1) → commits s ts use →
    ∀ p ∈ c.guards,
      stateAt s ts use p.1 = stateAt s ts chk p.1 ∧
      ∀ j, chk ≤ j → j < use → commits s ts j → p.1 ∉ (ts j).writes

def CheckHoldsSpec : Prop :=
  ∀ R₀ rev s ts chk use c own other,
    RevWF rev → GuardedStep R₀ rev s ts chk c (ts use) own other →
    chk ≤ use → commits s ts use → ¬ revokedAt R₀ rev s ts use other

def NoBifurcationSpec : Prop :=
  ∀ R₀ rev s ts chkA chkB iA iB cA cB xA xB,
    RevWF rev → GuardedStep R₀ rev s ts chkA cA (ts iA) xA xB →
    GuardedStep R₀ rev s ts chkB cB (ts iB) xB xA →
    cA.id ≠ cB.id → chkA ≤ iA → chkB ≤ iB →
    ¬ (commits s ts iA ∧ commits s ts iB)

def PriorWriterSpec : Prop :=
  ∀ s ts i,
    (∀ p ∈ (ts i).reads, p.2 = s p.1) → ¬ commits s ts i →
    ∃ p ∈ (ts i).reads, ∃ j, committed s ts i j ∧ p.1 ∈ (ts j).writes

def FreshPairSpec : Prop :=
  ∀ s ts i j a b va vb,
    i ≠ j → a ∈ (ts i).writes → b ∈ (ts j).writes →
    (b, vb) ∈ (ts i).reads → (a, va) ∈ (ts j).reads →
    va ≤ s a → vb ≤ s b → ¬ (commits s ts i ∧ commits s ts j)

end Reconstruction
