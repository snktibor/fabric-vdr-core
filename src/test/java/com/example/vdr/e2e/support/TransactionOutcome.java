package com.example.vdr.e2e.support;

/**
 * Terminal classification of a single submit attempt.
 *
 * <p>The Fabric client reports three structurally different failures through one {@code Exception}
 * channel: the peers refused to endorse, an ordered transaction was invalidated at commit time, or
 * the transport never delivered anything. A test that cannot tell them apart reads "a guard did its
 * job" and "the network was down" as the same event, so every attempt made through {@link
 * TransactionExecutor} is labelled with one of these outcomes.
 */
public enum TransactionOutcome {

    /** Ordered, validated and written: ledger state changed. */
    COMMITTED,

    /** Ordered, then invalidated because the read set was stale (MVCC or phantom read). */
    MVCC_CONFLICT,

    /** The chaincode refused during endorsement; nothing was ever ordered. */
    ENDORSEMENT_REJECTED,

    /** Ordered, then invalidated for a reason other than a stale read set. */
    COMMIT_REJECTED,

    /** The transport failed before ordering; the ledger provably did not change. */
    NETWORK_FAILURE,

    /** Ordered, but the commit status could not be read back: the effect is unknown. */
    COMMIT_STATUS_UNKNOWN,

    /** Anything the classifier could not attribute to one of the cases above. */
    OTHER_FAILURE;

    public boolean isCommitted() {
        return this == COMMITTED;
    }

    /**
     * Returns whether a fresh attempt is safe. Only outcomes that provably left the ledger
     * unchanged qualify; {@link #COMMIT_STATUS_UNKNOWN} deliberately does not, because the
     * transaction may already be in a block.
     */
    public boolean isRetryable() {
        return this == NETWORK_FAILURE || this == MVCC_CONFLICT;
    }
}
