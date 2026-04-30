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

    /** Creates an empty DID document instance for JSON deserialization. */
    public DidDocument() {}

    /**
     * Creates a fully populated DID document record.
     *
     * @param id DID identifier
     * @param controller optional controller DID or authority reference
     * @param verificationMethod verification method entries as JSON objects
     * @param service service endpoint entries as JSON objects
     * @param ownerId transaction caller identity recorded as owner
     * @param status lifecycle status value
     */
    public DidDocument(
            final String id,
            final String controller,
            final List<JsonNode> verificationMethod,
            final List<JsonNode> service,
            final String ownerId,
            final RecordStatus status) {
        this.id = id;
        this.controller = controller;
        this.verificationMethod = verificationMethod;
        this.service = service;
        this.ownerId = ownerId;
        this.status = status;
    }

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
}
