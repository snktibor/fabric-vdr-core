package com.example.vdr.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Model-layer DTO representing a credential schema record on ledger.
 *
 * <p>The record stores issuer ownership, schema naming/versioning metadata, allowed credential
 * attributes, and lifecycle state used by append-only deactivation rules.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SchemaRecord {

    // Schema identifier (ledger key attribute).
    private String id;
    // Issuer DID that owns/created the schema.
    private String issuerId;
    // Human-readable name.
    private String name;
    // Semantic version string.
    private String version;
    // Allowed attribute names for credentials.
    private List<String> attrNames;
    // Recorded owner identity from the invoking client.
    private String ownerId;
    // Lifecycle status tracked on ledger (ACTIVE, INACTIVE).
    private RecordStatus status = RecordStatus.ACTIVE;

    /** Creates an empty schema instance for JSON deserialization frameworks. */
    public SchemaRecord() {}

    /**
     * Creates a schema record initialized with owner metadata.
     *
     * @param id unique schema identifier
     * @param issuerId issuer DID responsible for schema governance
     * @param name schema display name
     * @param version schema version identifier
     * @param attrNames allowed credential attribute names
     * @param ownerId transaction caller identity recorded as owner
     */
    public SchemaRecord(
            final String id,
            final String issuerId,
            final String name,
            final String version,
            final List<String> attrNames,
            final String ownerId) {
        this.id = id;
        this.issuerId = issuerId;
        this.name = name;
        this.version = version;
        this.attrNames = attrNames;
        this.ownerId = ownerId;
    }

    /**
     * Returns the schema identifier.
     *
     * @return schema id value
     */
    public String getId() {
        return id;
    }

    /**
     * Sets the schema identifier.
     *
     * @param id schema id to assign
     */
    public void setId(final String id) {
        this.id = id;
    }

    /**
     * Returns the issuer DID that owns this schema.
     *
     * @return issuer DID identifier
     */
    public String getIssuerId() {
        return issuerId;
    }

    /**
     * Sets the issuer DID that owns this schema.
     *
     * @param issuerId issuer DID identifier to assign
     */
    public void setIssuerId(final String issuerId) {
        this.issuerId = issuerId;
    }

    /**
     * Returns the human-readable schema name.
     *
     * @return schema name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the human-readable schema name.
     *
     * @param name schema name to assign
     */
    public void setName(final String name) {
        this.name = name;
    }

    /**
     * Returns the schema version identifier.
     *
     * @return schema version string
     */
    public String getVersion() {
        return version;
    }

    /**
     * Sets the schema version identifier.
     *
     * @param version schema version value
     */
    public void setVersion(final String version) {
        this.version = version;
    }

    /**
     * Returns the list of allowed credential attribute names.
     *
     * @return schema attribute name list
     */
    public List<String> getAttrNames() {
        return attrNames;
    }

    /**
     * Sets the list of allowed credential attribute names.
     *
     * @param attrNames schema attribute names to assign
     */
    public void setAttrNames(final List<String> attrNames) {
        this.attrNames = attrNames;
    }

    /**
     * Returns the owner identity recorded for authorization decisions.
     *
     * @return owner identity value
     */
    public String getOwnerId() {
        return ownerId;
    }

    /**
     * Sets the owner identity recorded for authorization decisions.
     *
     * @param ownerId owner identity to assign
     */
    public void setOwnerId(final String ownerId) {
        this.ownerId = ownerId;
    }

    /**
     * Returns the lifecycle status tracked on ledger.
     *
     * @return current record status
     */
    public RecordStatus getStatus() {
        return status;
    }

    /**
     * Sets the lifecycle status used by append-only transitions.
     *
     * @param status lifecycle status to assign
     */
    public void setStatus(final RecordStatus status) {
        this.status = status;
    }
}
