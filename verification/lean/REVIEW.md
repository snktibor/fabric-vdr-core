# Semantic review and dispositions

[AGENT] A separate read-only reviewer compared the frozen baseline with the paper's displayed statements. It found no semantic blocker. The reviewer did not establish original-source equivalence or implementation correspondence.

- Independent check prefixes and arbitrary interleavings are retained. If the earlier revocation commits before the later party checks, persistence contradicts its checked premise. If it occurs after that check, strict growth and the actual prefix pin preclude the later commit. The proof handles both through `check_holds_at_use`.
- `RevWF` plus an endorsed singleton write set implies that a party's own revoked credential is in the call's target registry. That relationship is not a missing premise. Revoked-history persistence is separate and remains an abstract credential interpretation.
- Call-ID inequality is used to infer distinct occurrence indices. The model never defines commitment using transaction equality or membership. The original M7 counterexample is not reproduced; its unavailable ID/membership encoding cannot be inferred from the prose.
- The checked fact holds immediately before the use. A transaction can itself revoke another credential in the same registry; no post-write preservation claim is made.
- The pair-alone paragraph is insufficient if only the first transaction is fresh. `Examples.ce_future_pin_both_commit` is the checked counterexample. The proposed correction retains freshness for both pins, as in `FreshPairSpec`; see `PAPER-COVERAGE.md`.
- Extra rejection can enable a later transaction, so actual commits are not a subset of the unfiltered execution's commits. The frozen extension proves equality against its explicit no-op replacement stream, and preserves occurrence indices/check ordering. `SkipExamples.skipped_writer_enables_later_commit` checks this distinction concretely.

[AGENT] Every baseline definition and target remains byte-for-byte equal to its pre-proof freeze. `CONTRACT.md` received one attribution-only correction, requested by the parent: the original user's approval is overall scope/execution, while tool policy, strict-write scope, witness requirements and the skip extension are parent-agent decisions informed by the paper. Original and corrected hashes are retained. No target was weakened.
