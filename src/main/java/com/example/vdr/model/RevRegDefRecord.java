package com.example.vdr.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Model-layer DTO representing a revocation registry definition ledger record.
 *
 * <p>The record captures issuer/credential-definition linkage, revocation capacity, and the public
 * cryptographic keys of the tails-free AnonCreds v2 (ALLOSAUR) revocation scheme. Published
 * revocation registry definitions are immutable per the AnonCreds specification. The {@code
 * publicKeys} field remains a {@link JsonNode} to preserve structured yet flexible key material
 * without constraining clients to a rigid Java subtype.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RevRegDefRecord {

    // Revocation registry definition identifier.
    private String id;
    // Issuer DID that owns the definition.
    private String issuerId;
    // Credential definition this registry revokes against.
    private String credDefId;
    // Revocation scheme identifier (AnonCreds v2 ALLOSAUR).
    private String type;
    // Public cryptographic revocation keys for verifiers (JSON).
    private JsonNode publicKeys;
    // Maximum number of credentials the registry can track.
    private long maxCredNum;
    // Recorded owner DID resolved from verified JWT signer.
    private String ownerId;

    /** Creates an empty record instance for JSON deserialization frameworks. */
    public RevRegDefRecord() {}

    /**
     * Creates a revocation registry definition record.
     *
     * <p>Services use this constructor when persisting a newly created definition so ownership
     * metadata is applied consistently.
     *
     * @param id unique revocation registry definition identifier
     * @param issuerId issuer DID responsible for the definition
     * @param credDefId referenced credential definition identifier
     * @param type revocation scheme identifier (AnonCreds v2 ALLOSAUR)
     * @param publicKeys public cryptographic revocation keys exposed to verifiers
     * @param maxCredNum maximum number of credentials the registry can track
     * @param ownerId signer DID recorded as owner
     */
    public RevRegDefRecord(
            final String id,
            final String issuerId,
            final String credDefId,
            final String type,
            final JsonNode publicKeys,
            final long maxCredNum,
            final String ownerId) {
        this.id = id;
        this.issuerId = issuerId;
        this.credDefId = credDefId;
        this.type = type;
        this.publicKeys = publicKeys;
        this.maxCredNum = maxCredNum;
        this.ownerId = ownerId;
    }

    /**
     * Returns the revocation registry definition identifier.
     *
     * @return revocation registry definition id
     */
    public String getId() {
        return id;
    }

    /**
     * Updates the record identifier, typically during JSON binding.
     *
     * @param id revocation registry definition identifier to assign
     */
    public void setId(final String id) {
        this.id = id;
    }

    /**
     * Returns the issuer DID associated with this definition.
     *
     * @return issuer DID identifier
     */
    public String getIssuerId() {
        return issuerId;
    }

    /**
     * Sets the issuer DID associated with this definition.
     *
     * @param issuerId issuer DID identifier to assign
     */
    public void setIssuerId(final String issuerId) {
        this.issuerId = issuerId;
    }

    /**
     * Returns the credential definition identifier referenced by this definition.
     *
     * @return credential definition identifier
     */
    public String getCredDefId() {
        return credDefId;
    }

    /**
     * Sets the referenced credential definition identifier.
     *
     * @param credDefId credential definition identifier to assign
     */
    public void setCredDefId(final String credDefId) {
        this.credDefId = credDefId;
    }

    /**
     * Returns the revocation registry definition type descriptor.
     *
     * @return revocation scheme identifier
     */
    public String getType() {
        return type;
    }

    /**
     * Sets the revocation registry definition type descriptor.
     *
     * @param type revocation scheme identifier value
     */
    public void setType(final String type) {
        this.type = type;
    }

    /**
     * Returns the public revocation keys used by external verifiers.
     *
     * @return structured key material JSON node
     */
    public JsonNode getPublicKeys() {
        return publicKeys;
    }

    /**
     * Sets the public revocation keys payload for this definition.
     *
     * @param publicKeys structured key material JSON node
     */
    public void setPublicKeys(final JsonNode publicKeys) {
        this.publicKeys = publicKeys;
    }

    /**
     * Returns the maximum number of credentials this registry can track.
     *
     * @return registry capacity as credential count
     */
    public long getMaxCredNum() {
        return maxCredNum;
    }

    /**
     * Sets the maximum number of credentials this registry can track.
     *
     * @param maxCredNum registry capacity as credential count
     */
    public void setMaxCredNum(final long maxCredNum) {
        this.maxCredNum = maxCredNum;
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
