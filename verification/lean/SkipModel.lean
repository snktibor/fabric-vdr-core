import Model

namespace Reconstruction

def skipStateAt (s : State) (ts : Stream) (allow : Nat → Bool) : Nat → State
  | 0 => s
  | n + 1 => if allow n then step (skipStateAt s ts allow n) (ts n)
    else skipStateAt s ts allow n

def skipCommits (s : State) (ts : Stream) (allow : Nat → Bool) (i : Nat) : Prop :=
  allow i = true ∧ readsOk (skipStateAt s ts allow i) (ts i) = true

def skipRevokedAt (R₀ : Cred → Prop) (rev : Revocations)
    (s : State) (ts : Stream) (allow : Nat → Bool) (n : Nat) (x : Cred) : Prop :=
  R₀ x ∨ ∃ i, i < n ∧ skipCommits s ts allow i ∧ rev (ts i) x

def noop : Tx := ⟨0, [], []⟩

def erasedStream (ts : Stream) (allow : Nat → Bool) : Stream :=
  fun i => if allow i then ts i else noop

structure SkipGuardedStep (R₀ : Cred → Prop) (rev : Revocations)
    (s : State) (ts : Stream) (allow : Nat → Bool) (chk : Nat) (c : GuardedCall)
    (t : Tx) (own other : Cred) : Prop where
  checked : ¬ skipRevokedAt R₀ rev s ts allow chk other
  pinned : ∀ p ∈ c.guards, p.2 = skipStateAt s ts allow chk p.1
  guardsOther : ∃ v, (other.1, v) ∈ c.guards
  endorsed : ∃ peer : State, endorse peer c = some t
  revokesOwn : rev t own

def SkipStateSimulationSpec : Prop :=
  ∀ s ts allow n, skipStateAt s ts allow n = stateAt s (erasedStream ts allow) n

def SkipCommitSimulationSpec : Prop :=
  ∀ s ts allow i, allow i = true →
    (skipCommits s ts allow i ↔ commits s (erasedStream ts allow) i)

def SkipRevocationSimulationSpec : Prop :=
  ∀ R₀ rev s ts allow n x, RevWF rev →
    (skipRevokedAt R₀ rev s ts allow n x ↔
      revokedAt R₀ rev s (erasedStream ts allow) n x)

def SkipNoBifurcationSpec : Prop :=
  ∀ R₀ rev s ts allow chkA chkB iA iB cA cB xA xB,
    RevWF rev → SkipGuardedStep R₀ rev s ts allow chkA cA (ts iA) xA xB →
    SkipGuardedStep R₀ rev s ts allow chkB cB (ts iB) xB xA →
    cA.id ≠ cB.id → chkA ≤ iA → chkB ≤ iB →
    ¬ (skipCommits s ts allow iA ∧ skipCommits s ts allow iB)

def SkipRejectsSpec : Prop :=
  ∀ s ts allow i, allow i = false →
    ¬ skipCommits s ts allow i ∧
    skipStateAt s ts allow (i + 1) = skipStateAt s ts allow i

end Reconstruction
