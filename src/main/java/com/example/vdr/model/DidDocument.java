package com.example.vdr.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Model-layer DTO representing a DID document persisted on ledger.
 *
 * <p>The class keeps selected DID document fields in a format suitable for registry storage and
 * service validation, including owner attribution and lifecycle status for append-only state
 * transitions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DidDocument {

    // DID identifier (e.g., did:example:123).
    private String id;
    // Optional controller field from the DID document.
    private String controller;
    // Raw JSON entries for verification methods.
    private List<JsonNode> verificationMethod;
    // Raw JSON entries for services.
    private List<JsonNode> service;
    // Recorded owner DID resolved from verified JWT signer.
    private String ownerId;
    // Lifecycle status tracked on ledger.
    private RecordStatus status = RecordStatus.ACTIVE;
    // Client-declared creation time (ISO-8601); audit metadata, set server-side.
    private String createdAt;
    // Client-declared last-mutation time (ISO-8601); audit metadata, set server-side.
    private String updatedAt;

    /** Creates an empty DID document instance for JSON deserialization. */
    public DidDocument() {}

    /**
     * Returns the DID identifier.
     *
     * @return DID id value
     */
    public String getId() {
        return id;
    }

    /**
     * Sets the DID identifier.
     *
     * @param id DID id to assign
     */
    public void setId(final String id) {
        this.id = id;
    }

    /**
     * Returns the controller reference, if present.
     *
     * @return controller identifier or {@code null}
     */
    public String getController() {
        return controller;
    }

    /**
     * Sets the controller reference.
     *
     * @param controller controller identifier to assign
     */
    public void setController(final String controller) {
        this.controller = controller;
    }

    /**
     * Returns verification method entries associated with this DID.
     *
     * @return verification method JSON entries
     */
    public List<JsonNode> getVerificationMethod() {
        return verificationMethod;
    }

    /**
     * Sets verification method entries associated with this DID.
     *
     * @param verificationMethod verification method JSON entries
     */
    public void setVerificationMethod(final List<JsonNode> verificationMethod) {
        this.verificationMethod = verificationMethod;
    }

    /**
     * Returns service entries associated with this DID.
     *
     * @return service JSON entries
     */
    public List<JsonNode> getService() {
        return service;
    }

    /**
     * Sets service entries associated with this DID.
     *
     * @param service service JSON entries
     */
    public void setService(final List<JsonNode> service) {
        this.service = service;
    }

    /**
     * Returns the owner identity recorded for authorization checks.
     *
     * @return owner identity value
     */
    public String getOwnerId() {
        return ownerId;
    }

    /**
     * Sets the owner identity recorded for authorization checks.
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
     * @param status lifecycle status value
     */
    public void setStatus(final RecordStatus status) {
        this.status = status;
    }

    /**
     * Returns the creation timestamp recorded at registration (ISO-8601).
     *
     * <p>The value is the submitting client's channel-header transaction time: deterministic across
     * peers but chosen by the submitter, not attested by the network. Audit metadata only — never
     * build freshness or ordering guarantees on it. Any client-supplied value in the DID document
     * payload is overwritten server-side. {@code null} for records written before timestamps were
     * introduced.
     *
     * @return ISO-8601 creation time, or {@code null} for pre-timestamp records
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
     * Returns the last-mutation timestamp (ISO-8601), updated on update and deactivation.
     *
     * <p>Same trust caveat as {@link #getCreatedAt()}: client-declared channel-header time, audit
     * metadata only. {@code null} for records written before timestamps were introduced.
     *
     * @return ISO-8601 last-mutation time, or {@code null} for pre-timestamp records
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
