# Protocol model: scope and target statements

`Model.lean` holds the definitions and the target statements. This file explains them.
`frozen/semantic.sha256` seals both files, so `verify.sh` fails if either one changes and a
proof cannot quietly weaken what it proves. Proof files import the sealed model.

The model is written in Lean 4.23.0 with the standard library shipped with that release. It
uses no Mathlib and no external package, and every proof term is checked by the Lean kernel.

## Scope

- Keys and credential indices are natural numbers. A state is a total function `Key → Nat`,
  so every key already exists. The model has no absence, creation, deletion, bounded integer
  arithmetic, serialization, cryptography or authorization. Creation at version 0 is covered
  by the [ledger refinement](../ledger-refinement/CONTRACT.md).
- The persistent revoked set is an abstract history. It does not prove accumulator
  cryptography or that the Java code updates the accumulator faithfully. How the model
  corresponds to the implementation is an open question here.

## State, validation and occurrences

- A transaction `Tx` has a natural ID, a finite list of `(key, version)` reads and a finite
  list of written keys. `readsOk` checks every read for exact equality. A successful `step`
  increments each written key once, even if the key is listed twice. A rejected step changes
  nothing.
- A stream is `Nat → Tx`, and `stateAt s ts n` runs its first `n` entries in order. Any
  finite prefix of any stream is covered; there is no bound on exploration and no fairness
  assumption. A finite list can be padded with empty transactions without changing anything
  before its end.
- `commits s ts i` is validation at occurrence `i`; `committed s ts n i` also requires
  `i < n`. Commit facts refer to occurrence indices, never to transaction membership or ID
  equality, so repeated transactions and IDs are allowed. In the main theorem the two calls
  have different IDs, which gives them different occurrence indices.

## Endorsement and protocol

- A call has an ID, a target and a finite list of guards. Endorsement rejects a guard whose
  version differs from the peer's; otherwise it builds the reads
  `(target, peer target) :: guards` and the write set `[target]`. The target read is always
  present. The low-level constructor allows an empty guard list so that the unguarded attack
  can be stated; every honest step guards the other credential's registry.
- `GuardedStep` requires that the other credential is unrevoked at the prefix where it is
  checked, that every guard is pinned to that prefix's version, that the guards cover the
  other credential's registry, that endorsement succeeds at some peer state, and that the
  step revokes its own credential. The theorem separately requires each check to come before
  its own use. Endorsing peers and check prefixes may differ, and either check may come after
  the other transaction; the proof excludes that case when both commits are assumed.
- `rev : Tx → Cred → Prop` gives the revocation effect of a transaction. `RevWF` requires
  `rev t x → x.registry ∈ t.writes`. `revokedAt` means revoked initially or by a committed
  occurrence in the prefix. Relating real accumulator checks to this history needs the
  paper's assumptions that the cryptography is secure and that each issuer updates its
  accumulator faithfully; `RevWF` does not verify them.

## Target statements

The exact statements are the `*Spec` propositions at the end of `Model.lean`.

1. `final_mono : FinalMonoSpec`: every key's version is monotone between ordered prefixes.
2. `strict_committed_write_growth : StrictGrowthSpec`: a committed occurrence strictly
   increases every key it writes.
3. `stale_never_commits : StaleNeverCommitsSpec`: a read that is already below the key's
   version at an earlier prefix cannot commit.
4. `tas_spec : TasSpec`: an endorsed transaction validates if and only if the target and all
   guards still have their exact versions.
5. `window_closed : WindowClosedSpec`: if the pins taken at the check prefix commit, the
   guarded versions did not change and no guarded key was written between check and use.
6. `check_holds_at_use : CheckHoldsSpec`: under `RevWF`, the checked credential is still
   unrevoked just before the committed use.
7. `no_bifurcation : NoBifurcationSpec`: two reciprocal honest steps with independent check
   prefixes cannot both commit, for any stream and interleaving. It assumes no initially
   fresh pair.
8. `rejected_has_prior_committed_writer : PriorWriterSpec`: if all reads are exact at the
   start and the occurrence is rejected, some earlier committed occurrence wrote a read key.
9. `at_most_one : FreshPairSpec`: two mutually guarding transactions whose pins are no newer
   than the initial state cannot both commit. This complements item 7, it does not replace it.

Items 8 and 9 are separate results outside the safety chain.

## Witnesses and axioms

`Examples.lean` gives kernel-checked concrete executions: honest steps that commit, an
unguarded run where both revocations commit, a guarded run where neither commits, two honest
checks at different prefixes with unrelated writes in between, and either party winning
depending on the order.

No `sorry`, `admit`, `native_decide` or custom axiom is allowed. `Audit.lean` prints the
axioms of every theorem in the model, and `verify.py` accepts only `propext` and
`Quot.sound`. The one exception is `rejected_has_prior_committed_writer`, which also uses
`Classical.choice` to extract a witness from a failed universal validation; nothing in the
safety chain depends on it.
