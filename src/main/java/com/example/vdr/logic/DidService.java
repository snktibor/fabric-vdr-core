package com.example.vdr.logic;

import com.example.vdr.exception.VdrException;
import com.example.vdr.ledger.ChaincodeEventEmitter;
import com.example.vdr.ledger.DidRegistry;
import com.example.vdr.ledger.RegistryEntry;
import com.example.vdr.ledger.VdrEvents;
import com.example.vdr.model.DidDocument;
import com.example.vdr.model.RecordStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Layer-3 business service for DID document lifecycle management.
 *
 * <p>This pure Java class enforces domain rules for DID creation, retrieval, listing, and soft
 * deactivation while delegating all ledger interaction to the injected {@link DidRegistry} from the
 * data-access layer.
 */
public class DidService {

    private final DidRegistry didRegistry;
    private final AuthorizationService authorizationService;
    private final ValidationService validationService;
    private final ChaincodeEventEmitter eventEmitter;
    private final Instant txTimestamp;

    /**
     * Creates a DID service with transaction-scoped dependencies.
     *
     * @param didRegistry registry used to persist and fetch DID documents
     * @param authorizationService owner authorization utility
     * @param validationService fail-fast validation utility
     * @param eventEmitter chaincode event emitter for state-change notifications
     * @param txTimestamp client-declared transaction time used for audit timestamps
     */
    public DidService(
            final DidRegistry didRegistry,
            final AuthorizationService authorizationService,
            final ValidationService validationService,
            final ChaincodeEventEmitter eventEmitter,
            final Instant txTimestamp) {
        this.didRegistry = didRegistry;
        this.authorizationService = authorizationService;
        this.validationService = validationService;
        this.eventEmitter = eventEmitter;
        this.txTimestamp = txTimestamp;
    }

    /**
     * Creates and persists a new DID document owned by the DID itself.
     *
     * <p>The method validates payload shape and id consistency, enforces unique identifier
     * constraints, and initializes lifecycle state to active.
     *
     * @param id unique DID identifier requested by caller
     * @param didDocumentJson DID document payload as JSON
     * @return created and persisted DID document
     * @throws VdrException if validation fails or the DID id already exists
     */
    public DidDocument createDid(final String id, final String didDocumentJson) {
        // Only the generic 'did:<method>:<method-specific-id>' syntax is enforced: the registry
        // stays method-agnostic by design, and no DID method carries privilege here. The check is
        // not cosmetic — every AnonCreds object id is a DID URL rooted at its issuer's DID, so an
        // ill-formed DID registered here would produce ill-formed object ids downstream.
        final String normalizedId = validationService.requireValidDid(id, "id");
        final DidDocument document =
                validationService.parseJson(didDocumentJson, DidDocument.class);

        validationService.requireNotBlank(document.getId(), "did document id");
        validationService.requireMatchingIds(normalizedId, document.getId(), "did document id");
        validationService.validateDidDocumentShape(document);

        if (didRegistry.exists(normalizedId)) {
            throw VdrException.duplicate("did", normalizedId);
        }

        document.setOwnerId(document.getId());
        document.setStatus(RecordStatus.ACTIVE);
        // Timestamps are service-controlled: any client-supplied values are overwritten.
        document.setCreatedAt(txTimeIso());
        document.setUpdatedAt(txTimeIso());
        didRegistry.put(normalizedId, document);
        eventEmitter.emit(VdrEvents.DID_CREATED, didEventPayload(normalizedId));
        return document;
    }

    /**
     * Resolves a DID document by identifier.
     *
     * @param id DID identifier to resolve
     * @return resolved DID document
     * @throws VdrException if identifier is blank or DID is not found
     */
    public DidDocument getDid(final String id) {
        final String normalizedId = validationService.requireNotBlank(id, "id");
        final DidDocument document = didRegistry.get(normalizedId);
        if (document == null) {
            throw VdrException.notFound("did", normalizedId);
        }
        return document;
    }

    /**
     * Lists all DID records currently stored on ledger.
     *
     * @return registry entry list containing DID ids and values
     */
    public List<RegistryEntry<DidDocument>> listDids() {
        return didRegistry.listAll();
    }

    /**
     * Performs append-only soft deactivation of a DID.
     *
     * <p>Ownership is enforced before status transition so only the recorded owner can change a DID
     * lifecycle state from active to inactive.
     *
     * @param signerDid DID resolved from verified JWT issuer claim
     * @param id DID identifier to deactivate
     * @return updated DID document with inactive status
     * @throws VdrException if DID is missing or caller is not the owner
     */
    public DidDocument deactivateDid(final String signerDid, final String id) {
        final DidDocument document = getDid(id);
        authorizationService.assertSignerOwnsEntity(
                signerDid, document.getOwnerId(), "did", document.getId());
        document.setStatus(RecordStatus.INACTIVE);
        document.setUpdatedAt(txTimeIso());
        didRegistry.put(document.getId(), document);
        eventEmitter.emit(VdrEvents.DID_DEACTIVATED, didEventPayload(document.getId()));
        return document;
    }

    /**
     * Updates a DID document while preserving ownership and lifecycle state.
     *
     * <p>Only the owner may update, and deactivated DIDs are immutable.
     *
     * @param signerDid DID resolved from verified JWT issuer claim
     * @param id DID identifier to update
     * @param didDocumentJson replacement DID document payload
     * @return updated DID document as persisted on ledger
     * @throws VdrException if DID is missing, caller is not owner, DID is inactive, or payload is
     *     invalid
     */
    public DidDocument updateDid(
            final String signerDid, final String id, final String didDocumentJson) {
        final String normalizedId = validationService.requireNotBlank(id, "id");
        final DidDocument existing = getDid(normalizedId);

        authorizationService.assertSignerOwnsEntity(
                signerDid, existing.getOwnerId(), "did", existing.getId());
        if (existing.getStatus() != RecordStatus.ACTIVE) {
            throw VdrException.unauthorized(
                    "authorization failed: did '" + normalizedId + "' is not active");
        }

        final DidDocument updated = validationService.parseJson(didDocumentJson, DidDocument.class);
        validationService.requireNotBlank(updated.getId(), "did document id");
        validationService.requireMatchingIds(normalizedId, updated.getId(), "did document id");
        validationService.validateDidDocumentShape(updated);

        updated.setOwnerId(existing.getOwnerId());
        updated.setStatus(existing.getStatus());
        // Carry the original creation time over to the replacement document; a client-supplied
        // createdAt in the payload is discarded, so an update can never restamp the record.
        updated.setCreatedAt(existing.getCreatedAt());
        updated.setUpdatedAt(txTimeIso());
        didRegistry.put(normalizedId, updated);
        eventEmitter.emit(VdrEvents.DID_UPDATED, didEventPayload(normalizedId));
        return updated;
    }

    /**
     * Ensures that a DID exists and is currently active.
     *
     * <p>Downstream service flows use this guard when they depend on a valid, non-deactivated DID
     * before processing related entities.
     *
     * @param didId DID identifier to validate
     * @throws VdrException if DID is missing or not in active status
     */
    public void assertDidActive(final String didId) {
        final DidDocument document = getDid(didId);
        if (document.getStatus() != RecordStatus.ACTIVE) {
            throw VdrException.invalid("did '" + didId + "' is not active");
        }
    }

    /**
     * Builds the lean DID lifecycle event payload.
     *
     * @param id DID identifier the event refers to
     * @return payload carrying the DID id
     */
    private Map<String, Object> didEventPayload(final String id) {
        return Map.of("id", id);
    }

    /**
     * Renders the client-declared transaction time as an ISO-8601 string.
     *
     * @return ISO-8601 transaction time
     */
    private String txTimeIso() {
        return txTimestamp.toString();
    }
}
