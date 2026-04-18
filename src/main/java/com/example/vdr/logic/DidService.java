package com.example.vdr.logic;

import com.example.vdr.exception.VdrException;
import com.example.vdr.ledger.DidRegistry;
import com.example.vdr.ledger.RegistryEntry;
import com.example.vdr.model.DidDocument;
import com.example.vdr.model.RecordStatus;
import java.util.List;

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

    /**
     * Creates a DID service with transaction-scoped dependencies.
     *
     * @param didRegistry registry used to persist and fetch DID documents
     * @param authorizationService owner authorization utility
     * @param validationService fail-fast validation utility
     */
    public DidService(
            final DidRegistry didRegistry,
            final AuthorizationService authorizationService,
            final ValidationService validationService) {
        this.didRegistry = didRegistry;
        this.authorizationService = authorizationService;
        this.validationService = validationService;
    }

    /**
     * Creates and persists a new DID document owned by the caller.
     *
     * <p>The method validates payload shape and id consistency, enforces unique identifier
     * constraints, and initializes lifecycle state to active.
     *
     * @param callerId identity of transaction invoker becoming owner
     * @param id unique DID identifier requested by caller
     * @param didDocumentJson DID document payload as JSON
     * @return created and persisted DID document
     * @throws VdrException if validation fails or the DID id already exists
     */
    public DidDocument createDid(
            final String callerId, final String id, final String didDocumentJson) {
        final String normalizedId = validationService.requireNotBlank(id, "id");
        final DidDocument document =
                validationService.parseJson(didDocumentJson, DidDocument.class);

        validationService.requireNotBlank(document.getId(), "did document id");
        validationService.requireMatchingIds(normalizedId, document.getId(), "did document id");
        validationService.validateDidDocumentShape(document);

        if (didRegistry.exists(normalizedId)) {
            throw VdrException.duplicate("did", normalizedId);
        }

        document.setOwnerId(callerId);
        document.setStatus(RecordStatus.ACTIVE);
        didRegistry.put(normalizedId, document);
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
     * @param callerId identity of transaction invoker
     * @param id DID identifier to deactivate
     * @return updated DID document with inactive status
     * @throws VdrException if DID is missing or caller is not the owner
     */
    public DidDocument deactivateDid(final String callerId, final String id) {
        final DidDocument document = getDid(id);
        authorizationService.assertCallerIsOwner(
                callerId, document.getOwnerId(), "did", document.getId());
        document.setStatus(RecordStatus.INACTIVE);
        didRegistry.put(document.getId(), document);
        return document;
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
}