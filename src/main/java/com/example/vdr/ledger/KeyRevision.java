package com.example.vdr.ledger;

import java.time.Instant;

/**
 * One historical revision of a ledger key, as reported by Fabric's history index.
 *
 * <p>The timestamp is the submitting client's channel-header transaction time: deterministic across
 * peers but chosen by the submitter, so it is audit metadata rather than a trusted clock. For
 * deleted (tombstone) revisions {@code value} is {@code null} and {@link #isDeleted()} is {@code
 * true}; consumers must check the flag before using the value.
 *
 * @param <T> deserialized record type of the key's value
 */
public final class KeyRevision<T> {

    private final String txId;
    private final Instant timestamp;
    private final boolean deleted;
    private final T value;

    /**
     * Creates an immutable revision snapshot.
     *
     * @param txId id of the transaction that wrote this revision
     * @param timestamp client-declared transaction timestamp of that write
     * @param deleted whether this revision is a deletion tombstone
     * @param value deserialized record value, or {@code null} for tombstones
     */
    public KeyRevision(
            final String txId, final Instant timestamp, final boolean deleted, final T value) {
        this.txId = txId;
        this.timestamp = timestamp;
        this.deleted = deleted;
        this.value = value;
    }

    /**
     * Returns the id of the transaction that wrote this revision.
     *
     * @return Fabric transaction id
     */
    public String getTxId() {
        return txId;
    }

    /**
     * Returns the client-declared transaction timestamp of this revision.
     *
     * @return channel-header transaction time
     */
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * Returns whether this revision deleted the key.
     *
     * @return {@code true} for deletion tombstones
     */
    public boolean isDeleted() {
        return deleted;
    }

    /**
     * Returns the record value written by this revision.
     *
     * @return deserialized value, or {@code null} when {@link #isDeleted()} is {@code true}
     */
    public T getValue() {
        return value;
    }
}
