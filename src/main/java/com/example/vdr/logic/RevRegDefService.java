package com.example.vdr.logic;

import com.example.vdr.exception.VdrException;
import com.example.vdr.ledger.ChaincodeEventEmitter;
import com.example.vdr.ledger.CredentialDefinitionRegistry;
import com.example.vdr.ledger.DidRegistry;
import com.example.vdr.ledger.RegistryEntry;
import com.example.vdr.ledger.RevRegDefRegistry;
import com.example.vdr.ledger.VdrEvents;
import com.example.vdr.model.CredentialDefinitionRecord;
import com.example.vdr.model.DidDocument;
import com.example.vdr.model.RecordStatus;
import com.example.vdr.model.RevRegDefRecord;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Layer-3 business service for revocation registry definition lifecycle operations.
 *
 * <p>This pure Java service orchestrates validation, ownership policy, and referential integrity
 * checks while delegating all ledger I/O to injected registries from the data-access layer.
 * Published revocation registry definitions follow the tails-free AnonCreds v2 (ALLOSAUR)
 * revocation scheme and are immutable once created: no update or deactivation transitions exist.
 */
public class RevRegDefService {

    private final RevRegDefRegistry revRegDefRegistry;
    private final CredentialDefinitionRegistry credDefRegistry;
    private final DidRegistry didRegistry;
    private final ValidationService validationService;
    private final ChaincodeEventEmitter eventEmitter;
    private final Instant txTimestamp;

    /**
     * Creates a revocation registry definition service with its required dependencies.
     *
     * @param revRegDefRegistry registry used to persist revocation registry definitions
     * @param credDefRegistry registry used to resolve referenced credential definitions
     * @param didRegistry registry used to resolve issuer DIDs
     * @param validationService fail-fast input and JSON validation utility
     * @param eventEmitter chaincode event emitter for state-change notifications
     * @param txTimestamp client-declared transaction time used for audit timestamps
     */
    public RevRegDefService(
            final RevRegDefRegistry revRegDefRegistry,
            final CredentialDefinitionRegistry credDefRegistry,
            final DidRegistry didRegistry,
            final ValidationService validationService,
            final ChaincodeEventEmitter eventEmitter,
            final Instant txTimestamp) {
        this.revRegDefRegistry = revRegDefRegistry;
        this.credDefRegistry = credDefRegistry;
        this.didRegistry = didRegistry;
        this.validationService = validationService;
        this.eventEmitter = eventEmitter;
        this.txTimestamp = txTimestamp;
    }

    /**
     * Creates and stores a new revocation registry definition owned by the caller.
     *
     * <p>The method enforces deterministic preconditions before persistence: required input fields,
     * UUIDv7-based id format, id uniqueness, referenced credential definition existence, issuer
     * consistency with the referenced credential definition, and active issuer DID status. The
     * {@code signerDid} argument is resolved from the verified DID-JWT upstream by the contract
     * layer; no token verification happens here.
     *
     * @param signerDid DID resolved from verified JWT issuer claim
     * @param id unique identifier for the revocation registry definition
     * @param issuerId issuer DID associated with the definition
     * @param credDefId referenced credential definition identifier
     * @param type revocation scheme identifier (AnonCreds v2 ALLOSAUR)
     * @param publicKeysJson public revocation keys JSON object for the definition
     * @param maxCredNum maximum number of credentials the registry can track
     * @return created and persisted revocation registry definition record
     * @throws VdrException if validation fails, dependencies are missing, issuer is inactive or
     *     inconsistent with the referenced credential definition, or id already exists
     */
    public RevRegDefRecord createRevRegDef(
            final String signerDid,
            final String id,
            final String issuerId,
            final String credDefId,
            final String type,
            final String publicKeysJson,
            final long maxCredNum) {
        final String normalizedId =
                validationService.requireValidEntityId(id, "revregdef:vdr:", "id");
        final String normalizedIssuerId = validationService.requireNotBlank(issuerId, "issuerId");
        final String normalizedCredDefId =
                validationService.requireNotBlank(credDefId, "credDefId");
        final String normalizedType = validationService.requireNotBlank(type, "type");
        final JsonNode publicKeys = validationService.parseJsonObject(publicKeysJson, "publicKeys");
        if (maxCredNum <= 0) {
            throw VdrException.invalid("maxCredNum must be greater than zero");
        }

        if (!normalizedIssuerId.equals(signerDid)) {
            throw VdrException.unauthorized(
                    "Unauthorized: signer DID must match issuerId for revocation registry"
                            + " definition creation");
        }

        if (revRegDefRegistry.exists(normalizedId)) {
            throw VdrException.duplicate("revregdef", normalizedId);
        }

        final CredentialDefinitionRecord credDefRecord = credDefRegistry.get(normalizedCredDefId);
        if (credDefRecord == null) {
            throw VdrException.notFound("creddef", normalizedCredDefId);
        }
        if (!normalizedIssuerId.equals(credDefRecord.getIssuerId())) {
            throw VdrException.invalid(
                    "issuerId '"
                            + normalizedIssuerId
                            + "' does not match issuer of referenced creddef '"
                            + normalizedCredDefId
                            + "'");
        }

        final DidDocument issuer = didRegistry.get(normalizedIssuerId);
        if (issuer == null) {
            throw VdrException.notFound("issuer DID", normalizedIssuerId);
        }
        if (issuer.getStatus() != RecordStatus.ACTIVE) {
            throw VdrException.invalid("issuer DID '" + normalizedIssuerId + "' is not active");
        }

        final RevRegDefRecord revRegDefRecord =
                new RevRegDefRecord(
                        normalizedId,
                        normalizedIssuerId,
                        normalizedCredDefId,
                        normalizedType,
                        publicKeys,
                        maxCredNum,
                        signerDid);

        revRegDefRecord.setCreatedAt(txTimestamp.toString());

        revRegDefRegistry.put(normalizedId, revRegDefRecord);
        eventEmitter.emit(
                VdrEvents.REV_REG_DEF_CREATED,
                Map.of(
                        "id",
                        normalizedId,
                        "issuerId",
                        normalizedIssuerId,
                        "credDefId",
                        normalizedCredDefId));
        return revRegDefRecord;
    }

    /**
     * Resolves a revocation registry definition by identifier.
     *
     * @param id revocation registry definition identifier
     * @return resolved revocation registry definition record
     * @throws VdrException if the identifier is blank or record is missing
     */
    public RevRegDefRecord getRevRegDef(final String id) {
        final String normalizedId = validationService.requireNotBlank(id, "id");
        final RevRegDefRecord revRegDefRecord = revRegDefRegistry.get(normalizedId);
        if (revRegDefRecord == null) {
            throw VdrException.notFound("revregdef", normalizedId);
        }
        return revRegDefRecord;
    }

    /**
     * Lists all revocation registry definition entries from ledger namespace.
     *
     * @return immutable-style registry projections containing id and value
     */
    public List<RegistryEntry<RevRegDefRecord>> listRevRegDefs() {
        return revRegDefRegistry.listAll();
    }
}
