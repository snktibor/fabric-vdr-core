package com.example.vdr.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Model-layer DTO representing credential metadata persisted on ledger.
 *
 * <p>The full credential payload remains off-ledger; this record stores integrity and reference
 * information (hash, issuance time, links, and owner/status) needed for verification and lifecycle
 * management.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CredentialRecord {

    // Credential identifier.
    private String id;
    // Credential definition id used to issue.
    private String credDefId;
    // Subject DID bound to credential.
    private String subjectDid;
    // Hash of canonical credential JSON.
    private String credentialHash;
    // Issuance timestamp (epoch millis).
    private long issuanceDate;
    // Lifecycle status (active/revoked).
    private RecordStatus status = RecordStatus.ACTIVE;
    // Recorded owner DID resolved from verified JWT signer.
    private String ownerId;

    /** Creates an empty record instance for JSON deserialization frameworks. */
    public CredentialRecord() {}

    /**
     * Creates a fully populated credential metadata record.
     *
     * @param id unique credential identifier
     * @param credDefId credential definition used for issuance
     * @param subjectDid subject DID bound to the credential
     * @param credentialHash SHA-256 hash of canonical credential JSON
     * @param issuanceDate issuance timestamp in epoch milliseconds
     * @param status lifecycle status value
     * @param ownerId transaction caller identity recorded as owner
     */
    public CredentialRecord(
            final String id,
            final String credDefId,
            final String subjectDid,
            final String credentialHash,
            final long issuanceDate,
            final RecordStatus status,
            final String ownerId) {
        this.id = id;
        this.credDefId = credDefId;
        this.subjectDid = subjectDid;
        this.credentialHash = credentialHash;
        this.issuanceDate = issuanceDate;
        this.status = status;
        this.ownerId = ownerId;
    }

    /**
     * Returns the credential identifier.
     *
     * @return credential id
     */
    public String getId() {
        return id;
    }

    /**
     * Sets the credential identifier.
     *
     * @param id credential id to assign
     */
    public void setId(final String id) {
        this.id = id;
    }

    /**
     * Returns the linked credential definition identifier.
     *
     * @return credential definition id
     */
    public String getCredDefId() {
        return credDefId;
    }

    /**
     * Sets the linked credential definition identifier.
     *
     * @param credDefId credential definition id to assign
     */
    public void setCredDefId(final String credDefId) {
        this.credDefId = credDefId;
    }

    /**
     * Returns the subject DID associated with this credential.
     *
     * @return subject DID identifier
     */
    public String getSubjectDid() {
        return subjectDid;
    }

    /**
     * Sets the subject DID associated with this credential.
     *
     * @param subjectDid subject DID identifier to assign
     */
    public void setSubjectDid(final String subjectDid) {
        this.subjectDid = subjectDid;
    }

    /**
     * Returns the canonical credential content hash.
     *
     * @return credential hash string
     */
    public String getCredentialHash() {
        return credentialHash;
    }

    /**
     * Sets the canonical credential content hash.
     *
     * @param credentialHash hash value to assign
     */
    public void setCredentialHash(final String credentialHash) {
        this.credentialHash = credentialHash;
    }

    /**
     * Returns the issuance timestamp in epoch milliseconds.
     *
     * @return issuance timestamp
     */
    public long getIssuanceDate() {
        return issuanceDate;
    }

    /**
     * Sets the issuance timestamp in epoch milliseconds.
     *
     * @param issuanceDate issuance timestamp to assign
     */
    public void setIssuanceDate(final long issuanceDate) {
        this.issuanceDate = issuanceDate;
    }

    /**
     * Returns the lifecycle status of this credential record.
     *
     * @return current record status
     */
    public RecordStatus getStatus() {
        return status;
    }

    /**
     * Sets the lifecycle status used by append-only transitions.
     *
     * @param status status value to assign
     */
    public void setStatus(final RecordStatus status) {
        this.status = status;
    }

    /**
     * Returns the owner identity used for authorization checks.
     *
     * @return owner identity string
     */
    public String getOwnerId() {
        return ownerId;
    }

    /**
     * Sets the owner identity used for authorization checks.
     *
     * @param ownerId owner identity value
     */
    public void setOwnerId(final String ownerId) {
        this.ownerId = ownerId;
    }
}
