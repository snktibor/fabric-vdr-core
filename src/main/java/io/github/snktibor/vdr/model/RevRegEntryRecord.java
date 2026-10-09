package io.github.snktibor.vdr.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Model-layer DTO representing a revocation registry entry ledger record.
 *
 * <p>The record holds the current cryptographic accumulator value for exactly one revocation
 * registry definition under the tails-free VB20 accumulator revocation scheme. The {@code
 * accumValue} field is the off-chain-computed compressed BLS12-381 G1 accumulator point in
 * canonical lowercase-hex form, stored verbatim after format validation: the chaincode never
 * performs accumulator cryptography on it. There is deliberately no bit-string or status-list
 * field, which would reintroduce a correlatable v1 construct. Unlike other VDR records, the entry
 * is mutable: each revocation replaces the accumulator value in place and increments the audit
 * {@code version}.
 *
 * <p>The entry carries no identifier and no owner of its own. It exists one-to-one with its parent
 * revocation registry definition, so {@code revRegDefId} is both its identity and its storage key,
 * and every authorization decision reads the owner recorded on that parent definition.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RevRegEntryRecord {

    // Parent revocation registry definition this entry belongs to.
    private String revRegDefId;
    // Off-chain-computed compressed G1 accumulator point (lowercase hex), stored verbatim.
    private String accumValue;
    // Monotonically increasing audit counter; 0 at genesis, +1 per update.
    private long version;
    // Client-declared creation time (ISO-8601); audit metadata, set server-side.
    private String createdAt;
    // Client-declared last-mutation time (ISO-8601); audit metadata, set server-side.
    private String updatedAt;

    /** Creates an empty record instance for JSON deserialization frameworks. */
    public RevRegEntryRecord() {}

    /**
     * Creates a revocation registry entry record.
     *
     * <p>Services use this constructor when persisting the genesis entry; the audit timestamps are
     * applied afterwards from the transaction context.
     *
     * @param revRegDefId parent revocation registry definition identifier
     * @param accumValue accumulator value submitted by the issuer (compressed G1 point as hex)
     * @param version monotonically increasing audit counter
     */
    public RevRegEntryRecord(
            final String revRegDefId, final String accumValue, final long version) {
        this.revRegDefId = revRegDefId;
        this.accumValue = accumValue;
        this.version = version;
    }

    /**
     * Returns the parent revocation registry definition identifier.
     *
     * @return revocation registry definition identifier
     */
    public String getRevRegDefId() {
        return revRegDefId;
    }

    /**
     * Sets the parent revocation registry definition identifier.
     *
     * @param revRegDefId revocation registry definition identifier to assign
     */
    public void setRevRegDefId(final String revRegDefId) {
        this.revRegDefId = revRegDefId;
    }

    /**
     * Returns the accumulator value currently published for verifiers.
     *
     * @return verbatim off-chain-computed accumulator value
     */
    public String getAccumValue() {
        return accumValue;
    }

    /**
     * Sets the accumulator value published for verifiers.
     *
     * @param accumValue verbatim off-chain-computed accumulator value
     */
    public void setAccumValue(final String accumValue) {
        this.accumValue = accumValue;
    }

    /**
     * Returns the monotonically increasing audit version of this entry.
     *
     * @return audit counter, 0 at genesis and incremented per update
     */
    public long getVersion() {
        return version;
    }

    /**
     * Sets the monotonically increasing audit version of this entry.
     *
     * @param version audit counter value
     */
    public void setVersion(final long version) {
        this.version = version;
    }

    /**
     * Returns the creation timestamp recorded at genesis (ISO-8601).
     *
     * <p>The value is the submitting client's channel-header transaction time: deterministic across
     * peers but chosen by the submitter, not attested by the network. Audit metadata only — never
     * build freshness or ordering guarantees on it; ordering of entry revisions is what {@code
     * version} is for.
     *
     * @return ISO-8601 creation time, never {@code null} for a persisted record
     */
    public String getCreatedAt() {
        return createdAt;
    }

    /**
     * Sets the creation timestamp (service-controlled).
     *
     * @param createdAt ISO-8601 transaction time
     */
    public void setCreatedAt(final String createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * Returns the last accumulator-replacement timestamp (ISO-8601).
     *
     * <p>Same trust caveat as {@link #getCreatedAt()}: client-declared channel-header time, audit
     * metadata only.
     *
     * @return ISO-8601 last-mutation time, never {@code null} for a persisted record
     */
    public String getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Sets the last-mutation timestamp (service-controlled).
     *
     * @param updatedAt ISO-8601 transaction time
     */
    public void setUpdatedAt(final String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
