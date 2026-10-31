# Preserved failed statement and explicit correction

During the first proof-file compile, Lean exposed a precedence error in the initial frozen `EndorsementSpec`. Its unparenthesized `mode = update → eligible = true ↔ conditions` parses as `(mode = update → eligible = true) ↔ conditions`. For a creation proposal at an absent key, the left side is vacuously true and the right side is false. That statement cannot be proved.

The original frozen model, contract and spec remain byte-identical. `LedgerSpecCorrection.lean` separately freezes `EndorsementCorrectedSpec`, which puts the update-mode premise outside the equivalence. This is the endorsement property intended by the prose contract. No transition semantics, acceptance criterion, safety statement or simulation target changes. Replay must check a counterexample refuting the preserved initial target, the corrected target and all ten other original targets. Results must disclose ten original targets proved, one original target refuted and one corrected target proved, never eleven original targets proved.

The correction was frozen after the first proof-search round exposed the parsing error. The correctly scoped helper `eligible_update_iff` had already compiled from the unchanged concrete definition in that round; the correction freeze does not falsely claim to precede that discovery.
