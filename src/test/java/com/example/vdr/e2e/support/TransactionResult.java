package com.example.vdr.e2e.support;

import org.hyperledger.fabric.client.Status;
import org.hyperledger.fabric.protos.peer.TxValidationCode;

/**
 * What one submit attempt actually did, in a form an assertion can read.
 *
 * <p>Carries the classified {@link TransactionOutcome}, the peer's validation code where the
 * transaction reached validation at all, the block it landed in, and the originating exception.
 * "Failed" alone is not evidence; a concurrency scenario needs to distinguish a guard that fired
 * from a network that dropped.
 */
public final class TransactionResult {

    private static final long UNKNOWN_BLOCK = -1L;

    private final String label;
    private final String txName;
    private final String transactionId;
    private final TransactionOutcome outcome;
    private final TxValidationCode validationCode;
    private final long blockNumber;
    private final Exception failure;
    private final int attempts;

    private TransactionResult(
            final String label,
            final String txName,
            final String transactionId,
            final TransactionOutcome outcome,
            final TxValidationCode validationCode,
            final long blockNumber,
            final Exception failure,
            final int attempts) {
        this.label = label;
        this.txName = txName;
        this.transactionId = transactionId;
        this.outcome = outcome;
        this.validationCode = validationCode;
        this.blockNumber = blockNumber;
        this.failure = failure;
        this.attempts = attempts;
    }

    /** Result of a synchronous submit that returned without an exception. */
    public static TransactionResult committed(
            final String label, final String txName, final int attempts) {
        return new TransactionResult(
                label,
                txName,
                null,
                TransactionOutcome.COMMITTED,
                TxValidationCode.VALID,
                UNKNOWN_BLOCK,
                null,
                attempts);
    }

    /** Result built from a commit status read back for an ordered transaction. */
    public static TransactionResult fromStatus(
            final String label, final String txName, final Status status, final int attempts) {
        final TransactionOutcome outcome =
                status.isSuccessful()
                        ? TransactionOutcome.COMMITTED
                        : outcomeForValidationCode(status.getCode());
        return new TransactionResult(
                label,
                txName,
                status.getTransactionId(),
                outcome,
                status.getCode(),
                status.getBlockNumber(),
                null,
                attempts);
    }

    /** Result of an attempt that ended in an exception, classified by {@code outcome}. */
    public static TransactionResult failed(
            final String label,
            final String txName,
            final String transactionId,
            final TransactionOutcome outcome,
            final TxValidationCode validationCode,
            final Exception failure,
            final int attempts) {
        return new TransactionResult(
                label,
                txName,
                transactionId,
                outcome,
                validationCode,
                UNKNOWN_BLOCK,
                failure,
                attempts);
    }

    /** Maps a peer validation code onto the coarse outcome vocabulary. */
    public static TransactionOutcome outcomeForValidationCode(final TxValidationCode code) {
        if (code == TxValidationCode.VALID) {
            return TransactionOutcome.COMMITTED;
        }
        if (code == TxValidationCode.MVCC_READ_CONFLICT
                || code == TxValidationCode.PHANTOM_READ_CONFLICT) {
            return TransactionOutcome.MVCC_CONFLICT;
        }
        return TransactionOutcome.COMMIT_REJECTED;
    }

    public String label() {
        return label;
    }

    public String txName() {
        return txName;
    }

    public String transactionId() {
        return transactionId;
    }

    public TransactionOutcome outcome() {
        return outcome;
    }

    public TxValidationCode validationCode() {
        return validationCode;
    }

    public long blockNumber() {
        return blockNumber;
    }

    public Exception failure() {
        return failure;
    }

    public int attempts() {
        return attempts;
    }

    public boolean isCommitted() {
        return outcome.isCommitted();
    }

    /** One line naming every fact an assertion message should quote. */
    public String describe() {
        return String.format(
                "%s[tx=%s, outcome=%s, validationCode=%s, block=%s, txId=%s, attempts=%d, error=%s]",
                label,
                txName,
                outcome,
                validationCode == null ? "n/a" : validationCode.name(),
                blockNumber == UNKNOWN_BLOCK ? "n/a" : Long.toString(blockNumber),
                transactionId == null ? "n/a" : transactionId,
                attempts,
                failure == null ? "none" : failure.getMessage());
    }

    @Override
    public String toString() {
        return describe();
    }
}
