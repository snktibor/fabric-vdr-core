package io.github.snktibor.vdr.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Model-layer DTO representing a credential definition ledger record.
 *
 * <p>The record captures issuer/schema linkage, definition metadata, and owner attribution.
 * Published credential definitions are immutable per the AnonCreds specification. The {@code
 * publicMetadata} field remains a {@link JsonNode} to preserve structured yet flexible metadata
 * without constraining clients to a rigid Java subtype.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CredentialDefinitionRecord {

    // Credential definition identifier.
    private String id;
    // Issuer DID that owns the definition.
    private String issuerId;
    // Schema this definition references.
    private String schemaId;
    // Type or signature scheme.
    private String type;
    // Tag to distinguish multiple definitions.
    private String tag;
    // Public metadata for verifiers (JSON).
    private JsonNode publicMetadata;
    // Recorded owner DID resolved from verified JWT signer.
    private String ownerId;
    // Client-declared creation time (ISO-8601); audit metadata, set server-side.
    private String createdAt;

    /** Creates an empty record instance for JSON deserialization frameworks. */
    public CredentialDefinitionRecord() {}

    /**
     * Creates a credential definition record.
     *
     * <p>Services use this constructor when persisting a newly created definition so ownership
     * metadata is applied consistently.
     *
     * @param id unique credential definition identifier
     * @param issuerId issuer DID responsible for the definition
     * @param schemaId referenced schema identifier
     * @param type definition type or signature scheme
     * @param tag issuer-defined discriminator tag
     * @param publicMetadata public metadata exposed to verifiers
     * @param ownerId signer DID recorded as owner
     */
    public CredentialDefinitionRecord(
            final String id,
            final String issuerId,
            final String schemaId,
            final String type,
            final String tag,
            final JsonNode publicMetadata,
            final String ownerId) {
        this.id = id;
        this.issuerId = issuerId;
        this.schemaId = schemaId;
        this.type = type;
        this.tag = tag;
        this.publicMetadata = publicMetadata;
        this.ownerId = ownerId;
    }

    /**
     * Returns the credential definition identifier.
     *
     * @return credential definition id
     */
    public String getId() {
        return id;
    }

    /**
     * Updates the record identifier, typically during JSON binding.
     *
     * @param id credential definition identifier to assign
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
     * Returns the schema identifier referenced by this definition.
     *
     * @return schema identifier
     */
    public String getSchemaId() {
        return schemaId;
    }

    /**
     * Sets the referenced schema identifier.
     *
     * @param schemaId schema identifier to assign
     */
    public void setSchemaId(final String schemaId) {
        this.schemaId = schemaId;
    }

    /**
     * Returns the credential definition type descriptor.
     *
     * @return type or signature scheme name
     */
    public String getType() {
        return type;
    }

    /**
     * Sets the credential definition type descriptor.
     *
     * @param type type or signature scheme value
     */
    public void setType(final String type) {
        this.type = type;
    }

    /**
     * Returns the issuer-defined tag for this definition.
     *
     * @return tag used to distinguish definitions
     */
    public String getTag() {
        return tag;
    }

    /**
     * Sets the issuer-defined tag for this definition.
     *
     * @param tag discriminator tag value
     */
    public void setTag(final String tag) {
        this.tag = tag;
    }

    /**
     * Returns public metadata used by external verifiers.
     *
     * @return structured metadata JSON node
     */
    public JsonNode getPublicMetadata() {
        return publicMetadata;
    }

    /**
     * Sets the public metadata payload for this definition.
     *
     * @param publicMetadata structured metadata JSON node
     */
    public void setPublicMetadata(final JsonNode publicMetadata) {
        this.publicMetadata = publicMetadata;
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

    /**
     * Returns the creation timestamp recorded at publication (ISO-8601).
     *
     * <p>The value is the submitting client's channel-header transaction time: deterministic across
     * peers but chosen by the submitter, not attested by the network. Audit metadata only — never
     * build freshness or ordering guarantees on it.
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
}
