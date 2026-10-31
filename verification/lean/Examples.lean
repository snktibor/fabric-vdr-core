import Proofs

/- Concrete executions for the frozen reconstruction. -/
namespace Reconstruction.Examples

def initialState : State := fun _ => 0

def initiallyRevoked : Cred → Prop := fun _ => False

def credentialA : Cred := (0, 0)
def credentialB : Cred := (1, 0)

/-- Each revoking ID affects its own credential only when its registry is written. -/
def rev : Revocations := fun t x =>
  ((t.id = 10 ∧ x = credentialA) ∨ (t.id = 20 ∧ x = credentialB)) ∧
    x.1 ∈ t.writes

def callA : GuardedCall := ⟨10, 0, [(1, 0)]⟩
def callB : GuardedCall := ⟨20, 1, [(0, 0)]⟩
def txA : Tx := endorsedTx initialState callA
def txB : Tx := endorsedTx initialState callB

def idle : Tx := ⟨99, [], []⟩
def noise0 : Tx := ⟨30, [(2, 0)], [2]⟩
def noise1 : Tx := ⟨31, [(2, 1)], [2]⟩

/-- Two actual writes to key 2 surround the first protocol occurrence. -/
def pairStream (first second : Tx) : Stream
  | 0 => noise0
  | 1 => first
  | 2 => noise1
  | 3 => second
  | _ => idle

def guardedAB : Stream := pairStream txA txB
def guardedBA : Stream := pairStream txB txA

theorem rev_well_formed : RevWF rev := by
  intro t x h
  exact h.2

theorem fresh_at_zero (ts : Stream) (x : Cred) :
    ¬ revokedAt initiallyRevoked rev initialState ts 0 x := by
  simp [revokedAt, initiallyRevoked, committed]

theorem fresh_after_noise (first second : Tx) (x : Cred) :
    ¬ revokedAt initiallyRevoked rev initialState (pairStream first second) 1 x := by
  simp [revokedAt, initiallyRevoked, committed, Nat.lt_one_iff, pairStream, rev, noise0]

theorem guarded_transactions_exact :
    txA = ⟨10, [(0, 0), (1, 0)], [0]⟩ ∧
    txB = ⟨20, [(1, 0), (0, 0)], [1]⟩ ∧
    callA.id ≠ callB.id := by decide

/-- A checks at prefix 0 and is used at occurrence 1. -/
theorem stepA :
    GuardedStep initiallyRevoked rev initialState guardedAB 0 callA
      (guardedAB 1) credentialA credentialB := by
  refine ⟨fresh_at_zero _ _, ?_, ?_, ?_, ?_⟩
  · intro p hp
    rcases List.mem_singleton.mp hp with rfl
    rfl
  · exact ⟨0, by decide⟩
  · exact ⟨initialState, by rfl⟩
  · simp only [rev] <;> decide

/-- B checks at prefix 1, after noise0, and is used at occurrence 3. -/
theorem stepB :
    GuardedStep initiallyRevoked rev initialState guardedAB 1 callB
      (guardedAB 3) credentialB credentialA := by
  refine ⟨fresh_after_noise _ _ _, ?_, ?_, ?_, ?_⟩
  · intro p hp
    rcases List.mem_singleton.mp hp with rfl
    rfl
  · exact ⟨0, by decide⟩
  · exact ⟨initialState, by rfl⟩
  · simp only [rev] <;> decide

theorem stepB_reversed :
    GuardedStep initiallyRevoked rev initialState guardedBA 0 callB
      (guardedBA 1) credentialB credentialA := by
  refine ⟨fresh_at_zero _ _, ?_, ?_, ?_, ?_⟩
  · intro p hp
    rcases List.mem_singleton.mp hp with rfl
    rfl
  · exact ⟨0, by decide⟩
  · exact ⟨initialState, by rfl⟩
  · simp only [rev] <;> decide

theorem stepA_reversed :
    GuardedStep initiallyRevoked rev initialState guardedBA 1 callA
      (guardedBA 3) credentialA credentialB := by
  refine ⟨fresh_after_noise _ _ _, ?_, ?_, ?_, ?_⟩
  · intro p hp
    rcases List.mem_singleton.mp hp with rfl
    rfl
  · exact ⟨0, by decide⟩
  · exact ⟨initialState, by rfl⟩
  · simp only [rev] <;> decide

theorem guarded_A_wins :
    RevWF rev ∧
    GuardedStep initiallyRevoked rev initialState guardedAB 0 callA
      (guardedAB 1) credentialA credentialB ∧
    GuardedStep initiallyRevoked rev initialState guardedAB 1 callB
      (guardedAB 3) credentialB credentialA ∧
    callA.id ≠ callB.id ∧ (0 : Nat) < 1 ∧ (1 : Nat) < 3 ∧
    commits initialState guardedAB 1 ∧ ¬ commits initialState guardedAB 3 := by
  exact ⟨rev_well_formed, stepA, stepB, by decide, by decide, by decide,
    by unfold commits; decide, by unfold commits; decide⟩

theorem guarded_B_wins :
    RevWF rev ∧
    GuardedStep initiallyRevoked rev initialState guardedBA 1 callA
      (guardedBA 3) credentialA credentialB ∧
    GuardedStep initiallyRevoked rev initialState guardedBA 0 callB
      (guardedBA 1) credentialB credentialA ∧
    callA.id ≠ callB.id ∧ (1 : Nat) < 3 ∧ (0 : Nat) < 1 ∧
    commits initialState guardedBA 1 ∧ ¬ commits initialState guardedBA 3 := by
  exact ⟨rev_well_formed, stepA_reversed, stepB_reversed, by decide, by decide,
    by decide, by unfold commits; decide, by unfold commits; decide⟩

theorem unrelated_writers_commit_in_both_orders :
    commits initialState guardedAB 0 ∧ commits initialState guardedAB 2 ∧
    commits initialState guardedBA 0 ∧ commits initialState guardedBA 2 ∧
    noise0.writes = [2] ∧ noise1.writes = [2] ∧
    stateAt initialState guardedAB 4 2 = 2 ∧
    stateAt initialState guardedBA 4 2 = 2 := by
  unfold commits
  decide

theorem guarded_winners_preserve_checked_fact :
    ¬ revokedAt initiallyRevoked rev initialState guardedAB 1 credentialB ∧
    ¬ revokedAt initiallyRevoked rev initialState guardedBA 1 credentialA := by
  exact ⟨check_holds_at_use _ _ _ _ _ _ _ _ _ rev_well_formed stepA
      (by decide) (by unfold commits; decide),
    check_holds_at_use _ _ _ _ _ _ _ _ _ rev_well_formed stepB_reversed
      (by decide) (by unfold commits; decide)⟩

/- Removing the other-registry guard leaves both mandatory target reads intact. -/
def unguardedCallA : GuardedCall := ⟨10, 0, []⟩
def unguardedCallB : GuardedCall := ⟨20, 1, []⟩
def unguardedTxA : Tx := endorsedTx initialState unguardedCallA
def unguardedTxB : Tx := endorsedTx initialState unguardedCallB
def unguardedAB : Stream := pairStream unguardedTxA unguardedTxB

theorem unguarded_endorsements_and_exact_reads :
    endorse initialState unguardedCallA = some unguardedTxA ∧
    endorse initialState unguardedCallB = some unguardedTxB ∧
    unguardedCallA.guards = [] ∧ unguardedCallB.guards = [] ∧
    unguardedTxA = ⟨10, [(0, 0)], [0]⟩ ∧
    unguardedTxB = ⟨20, [(1, 0)], [1]⟩ ∧
    rev unguardedTxA credentialA ∧ rev unguardedTxB credentialB := by
  unfold rev
  decide

theorem unguarded_checks_initially_hold :
    ¬ revokedAt initiallyRevoked rev initialState unguardedAB 0 credentialB ∧
    ¬ revokedAt initiallyRevoked rev initialState unguardedAB 1 credentialA := by
  exact ⟨fresh_at_zero _ _, fresh_after_noise _ _ _⟩

theorem unguarded_both_commit :
    commits initialState unguardedAB 1 ∧ commits initialState unguardedAB 3 := by
  simp only [commits] <;> decide

theorem unguarded_second_checked_fact_false_at_use :
    ¬ revokedAt initiallyRevoked rev initialState unguardedAB 1 credentialA ∧
    commits initialState unguardedAB 3 ∧
    revokedAt initiallyRevoked rev initialState unguardedAB 3 credentialA := by
  refine ⟨unguarded_checks_initially_hold.2, unguarded_both_commit.2, ?_⟩
  exact Or.inr ⟨1, ⟨by decide, unguarded_both_commit.1⟩, by unfold rev; decide⟩

theorem unguarded_both_credentials_revoked :
    revokedAt initiallyRevoked rev initialState unguardedAB 4 credentialA ∧
    revokedAt initiallyRevoked rev initialState unguardedAB 4 credentialB := by
  constructor
  · exact Or.inr ⟨1, ⟨by decide, unguarded_both_commit.1⟩, by unfold rev; decide⟩
  · exact Or.inr ⟨3, ⟨by decide, unguarded_both_commit.2⟩, by unfold rev; decide⟩

theorem unguarded_calls_lack_required_coverage :
    ¬ (∃ v, (credentialB.1, v) ∈ unguardedCallA.guards) ∧
    ¬ (∃ v, (credentialA.1, v) ∈ unguardedCallB.guards) := by
  simp [unguardedCallA, unguardedCallB]

/- An earlier non-revoking registry update makes both honest calls fail. -/
def priorWriter : Tx := ⟨40, [(0, 0)], [0]⟩
def blockedAB : Stream
  | 0 => priorWriter
  | 1 => txA
  | 2 => txB
  | _ => idle

theorem blocked_stepA :
    GuardedStep initiallyRevoked rev initialState blockedAB 0 callA
      (blockedAB 1) credentialA credentialB := by
  refine ⟨fresh_at_zero _ _, ?_, ?_, ?_, ?_⟩
  · intro p hp
    rcases List.mem_singleton.mp hp with rfl
    rfl
  · exact ⟨0, by decide⟩
  · exact ⟨initialState, by rfl⟩
  · simp only [rev] <;> decide

theorem blocked_stepB :
    GuardedStep initiallyRevoked rev initialState blockedAB 0 callB
      (blockedAB 2) credentialB credentialA := by
  refine ⟨fresh_at_zero _ _, ?_, ?_, ?_, ?_⟩
  · intro p hp
    rcases List.mem_singleton.mp hp with rfl
    rfl
  · exact ⟨0, by decide⟩
  · exact ⟨initialState, by rfl⟩
  · simp only [rev] <;> decide

theorem guarded_neither_commits :
    RevWF rev ∧
    GuardedStep initiallyRevoked rev initialState blockedAB 0 callA
      (blockedAB 1) credentialA credentialB ∧
    GuardedStep initiallyRevoked rev initialState blockedAB 0 callB
      (blockedAB 2) credentialB credentialA ∧
    callA.id ≠ callB.id ∧ (0 : Nat) < 1 ∧ (0 : Nat) < 2 ∧
    commits initialState blockedAB 0 ∧
    ¬ commits initialState blockedAB 1 ∧ ¬ commits initialState blockedAB 2 := by
  exact ⟨rev_well_formed, blocked_stepA, blocked_stepB, by decide, by decide,
    by decide, by unfold commits; decide, by unfold commits; decide,
    by unfold commits; decide⟩

theorem guarded_neither_commit_rejection_causes :
    committed initialState blockedAB 1 0 ∧
    (0, 0) ∈ (blockedAB 1).reads ∧ 0 ∈ (blockedAB 0).writes ∧
    guardsOk (stateAt initialState blockedAB 1) callA = true ∧
    stateAt initialState blockedAB 1 callA.target = 1 ∧
    initialState callA.target = 0 ∧
    stateAt initialState blockedAB 2 callB.target = initialState callB.target ∧
    (0, 0) ∈ callB.guards ∧
    stateAt initialState blockedAB 2 0 = 1 ∧
    guardsOk (stateAt initialState blockedAB 2) callB = false := by
  unfold committed commits
  decide

/- Both initially fresh reads of the first transaction do not constrain a future
   pin in the second transaction. This concerns that weaker pair-alone premise;
   it does not refute FreshPairSpec or NoBifurcationSpec. -/
def futureInitialState : State := fun _ => 3
def futureTxA : Tx := ⟨50, [(0, 3), (1, 3)], [0]⟩
def futureTxB : Tx := ⟨51, [(1, 3), (0, 4)], [1]⟩
def futureCallA : GuardedCall := ⟨50, 0, [(1, 3)]⟩
def futurePinAB : Stream
  | 0 => futureTxA
  | 1 => futureTxB
  | _ => idle

theorem ce_future_pin_both_commit :
    futureInitialState 0 = 3 ∧ futureInitialState 1 = 3 ∧
    futureTxA.reads = [(0, 3), (1, 3)] ∧ futureTxA.writes = [0] ∧
    futureTxB.reads = [(1, 3), (0, 4)] ∧ futureTxB.writes = [1] ∧
    endorse futureInitialState futureCallA = some futureTxA ∧
    (∀ p ∈ futureTxA.reads, p.2 = futureInitialState p.1) ∧
    futureInitialState 0 < 4 ∧
    commits futureInitialState futurePinAB 0 ∧
    commits futureInitialState futurePinAB 1 ∧
    stateAt futureInitialState futurePinAB 2 0 = 4 ∧
    stateAt futureInitialState futurePinAB 2 1 = 4 := by
  refine ⟨by decide, by decide, by decide, by decide, by decide, by decide,
    by decide, ?_, by decide, by unfold commits; decide, by unfold commits; decide,
    by decide, by decide⟩
  simp [futureTxA, futureInitialState]

#print axioms rev_well_formed
#print axioms fresh_at_zero
#print axioms fresh_after_noise
#print axioms guarded_transactions_exact
#print axioms stepA
#print axioms stepB
#print axioms stepB_reversed
#print axioms stepA_reversed
#print axioms guarded_A_wins
#print axioms guarded_B_wins
#print axioms unrelated_writers_commit_in_both_orders
#print axioms guarded_winners_preserve_checked_fact
#print axioms unguarded_endorsements_and_exact_reads
#print axioms unguarded_checks_initially_hold
#print axioms unguarded_both_commit
#print axioms unguarded_second_checked_fact_false_at_use
#print axioms unguarded_both_credentials_revoked
#print axioms unguarded_calls_lack_required_coverage
#print axioms blocked_stepA
#print axioms blocked_stepB
#print axioms guarded_neither_commits
#print axioms guarded_neither_commit_rejection_causes
#print axioms ce_future_pin_both_commit

end Reconstruction.Examples
