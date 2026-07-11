package com.example.vdr.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Model-layer DTO representing a revocation registry entry ledger record.
 *
 * <p>The record holds the current cryptographic accumulator value for exactly one revocation
 * registry definition under the tails-free AnonCreds v2 (ALLOSAUR) revocation scheme. The {@code
 * accumValue} field is an opaque, off-chain-computed digest stored verbatim: the chaincode never
 * parses it and never derives it. There is deliberately no bit-string or status-list field, which
 * would reintroduce a correlatable v1 construct. Unlike other VDR records, the entry is mutable:
 * each revocation replaces the accumulator value in place and increments the audit {@code version}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RevRegEntryRecord {

    // Revocation registry entry identifier.
    private String id;
    // Parent revocation registry definition this entry belongs to.
    private String revRegDefId;
    // Opaque off-chain-computed accumulator value, stored verbatim.
    private String accumValue;
    // Monotonically increasing audit counter; 0 at genesis, +1 per update.
    private long version;
    // Recorded owner DID copied from the parent revocation registry definition.
    private String ownerId;

    /** Creates an empty record instance for JSON deserialization frameworks. */
    public RevRegEntryRecord() {}

    /**
     * Creates a revocation registry entry record.
     *
     * <p>Services use this constructor when persisting the genesis entry so ownership metadata is
     * copied consistently from the parent revocation registry definition.
     *
     * @param id unique revocation registry entry identifier
     * @param revRegDefId parent revocation registry definition identifier
     * @param accumValue opaque accumulator value submitted by the issuer
     * @param version monotonically increasing audit counter
     * @param ownerId owner DID copied from the parent revocation registry definition
     */
    public RevRegEntryRecord(
            final String id,
            final String revRegDefId,
            final String accumValue,
            final long version,
            final String ownerId) {
        this.id = id;
        this.revRegDefId = revRegDefId;
        this.accumValue = accumValue;
        this.version = version;
        this.ownerId = ownerId;
    }

    /**
     * Returns the revocation registry entry identifier.
     *
     * @return revocation registry entry id
     */
    public String getId() {
        return id;
    }

    /**
     * Updates the record identifier, typically during JSON binding.
     *
     * @param id revocation registry entry identifier to assign
     */
    public void setId(final String id) {
        this.id = id;
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
     * Returns the opaque accumulator value currently published for verifiers.
     *
     * @return verbatim off-chain-computed accumulator value
     */
    public String getAccumValue() {
        return accumValue;
    }

    /**
     * Sets the opaque accumulator value published for verifiers.
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
     * Returns the owner DID recorded for authorization checks.
     *
     * @return owner DID string
     */
    public String getOwnerId() {
        return ownerId;
    }

    /**
     * Sets the owner DID recorded for authorization checks.
     *
     * @param ownerId owner DID value
     */
    public void setOwnerId(final String ownerId) {
        this.ownerId = ownerId;
    }
}
