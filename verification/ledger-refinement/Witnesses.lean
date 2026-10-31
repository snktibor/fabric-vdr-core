import AtomicProofs

namespace LedgerRefinement.Witnesses
open Reconstruction
open Atomic

def initial : Ledger :=
  ⟨fun k => if k < 3 then some ⟨0, 42⟩ else none, fun k => 100 + k, fun k => 200 + k⟩
def permit : Nat → Bool := fun _ => true
def initiallyRevoked : Cred → Prop := fun _ => False
def effects : Effects := fun _ x => x.2 = 0
def xA : Cred := (0, 0)
def xB : Cred := (1, 0)
def callA : GuardedCall := ⟨10, 0, [(1, 0)]⟩
def callB : GuardedCall := ⟨11, 1, [(0, 0)]⟩
def callU : GuardedCall := ⟨12, 2, []⟩
def proposalA : Proposal := ⟨.update, callA, initial, 51, []⟩
def proposalB : Proposal := ⟨.update, callB, initial, 52, []⟩
def proposalU : Proposal := ⟨.update, callU, initial, 53, []⟩
def txA : Transaction := ⟨proposalA, [11]⟩
def txB : Transaction := ⟨proposalB, [12]⟩
def txU : Transaction := ⟨proposalU, [10]⟩
def tailTx : Transaction := ⟨⟨.extra, ⟨99, 99, []⟩, initial, 0, []⟩, []⟩
def race : Transactions
  | 0 => txU
  | 1 => txA
  | 2 => txB
  | _ => tailTx

theorem initial_wellformed : WellFormed initial := by
  intro k r hr
  simp only [initial] at hr
  split at hr
  · cases hr; decide
  · cases hr

theorem race_origins : Atomic.Origins initial race permit := by
  intro i
  refine ⟨0, Nat.zero_le _, ?_⟩
  cases i with
  | zero => rfl
  | succ i => cases i with
    | zero => rfl
    | succ i => cases i <;> rfl

theorem arithmetic_boundaries :
    nextVersion (maxLong - 1) = some maxLong ∧
    nextVersion maxLong = none ∧ nextVersion (-1) = none ∧ nextVersion 0 = some 1 := by
  decide

theorem plain_and_guarded_endorse : eligible proposalU = true ∧ eligible proposalA = true := by
  decide

theorem missing_target_rejected :
    eligible { proposalA with call := ⟨13, 9, []⟩ } = false := by decide

theorem missing_guard_rejected :
    eligible { proposalA with call := ⟨13, 0, [(9, 0)]⟩ } = false := by decide

theorem mismatching_guard_rejected :
    eligible { proposalA with call := ⟨13, 0, [(1, 1)]⟩ } = false := by decide

theorem self_guard_endorses :
    eligible { proposalA with call := ⟨13, 0, [(0, 0)]⟩ } = true := by decide

theorem repeated_guards_endorse :
    eligible { proposalA with call := ⟨13, 0, [(1, 0), (1, 0)]⟩ } = true := by decide

theorem unrelated_and_honest_commit :
    Atomic.commits initial race permit 0 ∧ Atomic.commits initial race permit 1 := by
  constructor <;> rfl

theorem stale_reciprocal_rejects : ¬ Atomic.commits initial race permit 2 := by
  intro h
  change false = true at h
  cases h

theorem registry_and_nonce_atomic :
    (Atomic.stateAt initial race permit 1).entries 2 = some ⟨1, 53⟩ ∧
    (Atomic.stateAt initial race permit 1).revision 2 = 103 ∧
    (Atomic.stateAt initial race permit 1).extraRevision 10 = 211 := by
  exact ⟨rfl, rfl, rfl⟩

theorem app_counter_is_not_revision :
    project (Atomic.stateAt initial race permit 1) 2 = 1 ∧
    (Atomic.stateAt initial race permit 1).revision 2 = 103 := by
  exact ⟨rfl, rfl⟩

theorem honest_A : Atomic.HonestStep initiallyRevoked effects initial race permit
    0 1 xA xB := by
  constructor
  · simp [Atomic.revokedAt, initiallyRevoked]
  · intro g hg
    have heq : g = (1, 0) := by simpa [race, txA, proposalA, callA] using hg
    subst g
    rfl
  · exact ⟨0, by simp [race, txA, proposalA, callA, xB]⟩
  · rfl
  · rfl
  · simp [concreteRevokes, race, txA, proposalA, callA, effects, xA]

theorem honest_B_later_check : Atomic.HonestStep initiallyRevoked effects initial race permit
    1 2 xB xA := by
  constructor
  · intro hr
    rcases hr with hr | ⟨i, hi, _, hrev⟩
    · exact hr
    · have heq : i = 0 := by omega
      subst i
      simp [concreteRevokes, race, txU, proposalU, callU, xA] at hrev
  · intro g hg
    have heq : g = (0, 0) := by simpa [race, txB, proposalB, callB] using hg
    subst g
    rfl
  · exact ⟨0, by simp [race, txB, proposalB, callB, xA]⟩
  · rfl
  · rfl
  · simp [concreteRevokes, race, txB, proposalB, callB, effects, xB]

theorem check_registry_presence_and_provenance :
    (Atomic.stateAt initial race permit 0).entries xB.1 = some ⟨0, 42⟩ ∧
    (Atomic.stateAt initial race permit 1).entries xA.1 = some ⟨0, 42⟩ ∧
    proposalA.peer.entries xB.1 = some ⟨0, 42⟩ ∧
    proposalB.peer.entries xA.1 = some ⟨0, 42⟩ := by
  exact ⟨rfl, rfl, rfl, rfl⟩

theorem asynchronous_theorem_applies :
    ¬ (Atomic.commits initial race permit 1 ∧ Atomic.commits initial race permit 2) := by
  exact Atomic.no_bifurcation initiallyRevoked effects initial race permit 0 1 1 2 xA xB
    initial_wellformed race_origins honest_A honest_B_later_check (by decide) (by omega) (by omega)

theorem committed_revocation_present :
    Atomic.revokedAt initiallyRevoked effects initial race permit 2 xA := by
  exact Or.inr ⟨1, by omega, unrelated_and_honest_commit.2, honest_A.revokesOwn⟩

theorem rejected_revocation_absent :
    ¬ Atomic.revokedAt initiallyRevoked effects initial race permit 3 xB := by
  intro hr
  rcases hr with hr | ⟨i, hi, hc, hrev⟩
  · exact hr
  · have hcases : i = 0 ∨ i = 1 ∨ i = 2 := by omega
    rcases hcases with rfl | rfl | rfl
    · simp [concreteRevokes, race, txU, proposalU, callU, xB] at hrev
    · simp [concreteRevokes, race, txA, proposalA, callA, xB] at hrev
    · exact stale_reciprocal_rejects hc

def extraConflict : Proposal := { proposalB with extraReads := [10] }

theorem extra_conflict_despite_matching_application_reads :
    eligible extraConflict = true ∧
    readsOk (project (Atomic.stateAt initial race permit 1)) (abstractTx extraConflict) = true ∧
    accepted (Atomic.stateAt initial race permit 1) extraConflict true = false := by
  exact ⟨rfl, rfl, rfl⟩

theorem stale_target_without_guards_rejects :
    eligible { proposalA with call := ⟨20, 0, []⟩ } = true ∧
    accepted (Atomic.stateAt initial race permit 2)
      { proposalA with call := ⟨20, 0, []⟩ } true = false := by
  exact ⟨rfl, rfl⟩

theorem external_rejection_exact_skip :
    accepted initial proposalA true = true ∧
    accepted initial proposalA false = false ∧
    Atomic.step initial txA false = initial := by
  exact ⟨rfl, rfl, rfl⟩

def createTx : Transaction := ⟨⟨.create, ⟨30, 3, []⟩, initial, 60, []⟩, [7]⟩
def created : Ledger := Atomic.step initial createTx true
def plainAfter : Transaction := ⟨⟨.update, ⟨31, 3, []⟩, created, 61, [7]⟩, [8]⟩
def lifecycle : Transactions
  | 0 => createTx
  | 1 => plainAfter
  | _ => tailTx

theorem lifecycle_origins : Atomic.Origins initial lifecycle permit := by
  intro i
  cases i with
  | zero => exact ⟨0, by omega, rfl⟩
  | succ i => cases i with
    | zero => exact ⟨1, by omega, rfl⟩
    | succ i => exact ⟨0, by omega, rfl⟩

theorem creation_zero_then_plain_one :
    Atomic.commits initial lifecycle permit 0 ∧ Atomic.commits initial lifecycle permit 1 ∧
    (Atomic.stateAt initial lifecycle permit 1).entries 3 = some ⟨0, 60⟩ ∧
    (Atomic.stateAt initial lifecycle permit 2).entries 3 = some ⟨1, 61⟩ ∧
    (Atomic.stateAt initial lifecycle permit 1).extraRevision 7 = 208 := by
  exact ⟨rfl, rfl, rfl, rfl, rfl⟩

theorem stale_creation_cannot_reset :
    accepted created createTx.proposal true = false ∧
    eligible { createTx.proposal with peer := created } = false := by
  exact ⟨rfl, rfl⟩

theorem lifecycle_state_transfer :
    project (Atomic.stateAt initial lifecycle permit 2) =
      skipStateAt (project initial) (Atomic.abstractStream lifecycle)
        (Atomic.allowance initial lifecycle permit) 2 := by
  exact Atomic.state_simulation initial lifecycle permit initial_wellformed lifecycle_origins 2

theorem duplicate_extra_writes_once :
    (Atomic.apply initial { txU with extraWrites := [10, 10] }).extraRevision 10 = 211 := by rfl

theorem extra_primary_also_listed_once :
    (Atomic.apply initial { tailTx with extraWrites := [99, 99] }).extraRevision 99 = 300 := by rfl

end LedgerRefinement.Witnesses
