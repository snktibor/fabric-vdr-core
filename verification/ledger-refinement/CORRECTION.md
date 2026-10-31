# The first `EndorsementSpec` is false

`EndorsementSpec` in `LedgerSpec.lean` was meant to say that an update proposal is eligible
exactly when its conditions hold. Written without parentheses,
`mode = update → eligible = true ↔ conditions` parses as
`(mode = update → eligible = true) ↔ conditions`. For a creation proposal at an absent key
the left side is vacuously true and the right side is false, so the statement cannot be
proved.

The statement is kept as written, and `frozen_endorsement_refuted` proves it false with a
counterexample. `LedgerSpecCorrection.lean` states `EndorsementCorrectedSpec`, which puts the
update-mode premise outside the equivalence, and `endorsement` proves it. No transition, no
acceptance rule, no safety statement and no simulation target changes. The replay therefore
reports ten of the first eleven targets proved, one refuted, and the corrected statement
proved.
