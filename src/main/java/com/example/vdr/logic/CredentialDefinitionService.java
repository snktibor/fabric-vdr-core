package com.example.vdr.logic;

import com.example.vdr.exception.VdrException;
import com.example.vdr.ledger.ChaincodeEventEmitter;
import com.example.vdr.ledger.CredentialDefinitionRegistry;
import com.example.vdr.ledger.DidRegistry;
import com.example.vdr.ledger.RegistryEntry;
import com.example.vdr.ledger.SchemaRegistry;
import com.example.vdr.ledger.VdrEvents;
import com.example.vdr.model.CredentialDefinitionRecord;
import com.example.vdr.model.DidDocument;
import com.example.vdr.model.RecordStatus;
import com.example.vdr.model.SchemaRecord;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Layer-3 business service for credential definition lifecycle operations.
 *
 * <p>This pure Java service orchestrates validation, ownership policy, and referential integrity
 * checks while delegating all ledger I/O to injected registries from the data-access layer.
 */
public class CredentialDefinitionService {

    private final CredentialDefinitionRegistry credDefRegistry;
    private final SchemaRegistry schemaRegistry;
    private final DidRegistry didRegistry;
    private final ValidationService validationService;
    private final ChaincodeEventEmitter eventEmitter;
    private final Instant txTimestamp;

    /**
     * Creates a credential definition service with its required dependencies.
     *
     * @param credDefRegistry registry used to persist credential definitions
     * @param schemaRegistry registry used to resolve referenced schemas
     * @param didRegistry registry used to resolve issuer DIDs
     * @param validationService fail-fast input and JSON validation utility
     * @param eventEmitter chaincode event emitter for state-change notifications
     * @param txTimestamp client-declared transaction time used for audit timestamps
     */
    public CredentialDefinitionService(
            final CredentialDefinitionRegistry credDefRegistry,
            final SchemaRegistry schemaRegistry,
            final DidRegistry didRegistry,
            final ValidationService validationService,
            final ChaincodeEventEmitter eventEmitter,
            final Instant txTimestamp) {
        this.credDefRegistry = credDefRegistry;
        this.schemaRegistry = schemaRegistry;
        this.didRegistry = didRegistry;
        this.validationService = validationService;
        this.eventEmitter = eventEmitter;
        this.txTimestamp = txTimestamp;
    }

    /**
     * Creates and stores a new credential definition owned by the caller.
     *
     * <p>The method enforces deterministic preconditions before persistence: required input fields,
     * an id that is a DID URL rooted at the declared issuer DID, id uniqueness, referenced schema
     * existence, and active issuer DID status.
     *
     * @param signerDid DID resolved from verified JWT issuer claim
     * @param id unique credential definition identifier as a DID URL rooted at the issuer DID
     * @param issuerId issuer DID associated with the definition
     * @param schemaId referenced schema identifier
     * @param type credential definition type descriptor
     * @param tag issuer-defined tag used for versioning/selection
     * @param publicMetadataJson public metadata JSON object for the definition
     * @return created and persisted credential definition record
     * @throws VdrException if validation fails, dependencies are missing, issuer is inactive, or id
     *     already exists
     */
    public CredentialDefinitionRecord createCredDef(
            final String signerDid,
            final String id,
            final String issuerId,
            final String schemaId,
            final String type,
            final String tag,
            final String publicMetadataJson) {
        // The issuer is validated first because the credential definition id must be rooted at it.
        final String normalizedIssuerId = validationService.requireValidDid(issuerId, "issuerId");
        final String normalizedId =
                validationService.requireValidObjectId(
                        id, normalizedIssuerId, AnonCredsObjectTypes.PUBLIC_CRED_DEF, "id");
        final String normalizedSchemaId = validationService.requireNotBlank(schemaId, "schemaId");
        final String normalizedType =
                validationService.requireAllowedType(type, VdrTypes.CRED_DEF_TYPES, "type");
        final String normalizedTag = validationService.requireNotBlank(tag, "tag");
        final JsonNode publicMetadata =
                validationService.parseJsonObject(publicMetadataJson, "publicMetadata");

        if (!normalizedIssuerId.equals(signerDid)) {
            throw VdrException.unauthorized(
                    "Unauthorized: signer DID must match issuerId for credential definition creation");
        }

        if (credDefRegistry.exists(normalizedId)) {
            throw VdrException.duplicate("creddef", normalizedId);
        }

        final SchemaRecord schemaRecord = schemaRegistry.get(normalizedSchemaId);
        if (schemaRecord == null) {
            throw VdrException.notFound("schema", normalizedSchemaId);
        }

        final DidDocument issuer = didRegistry.get(normalizedIssuerId);
        if (issuer == null) {
            throw VdrException.notFound("issuer DID", normalizedIssuerId);
        }
        if (issuer.getStatus() != RecordStatus.ACTIVE) {
            throw VdrException.invalid("issuer DID '" + normalizedIssuerId + "' is not active");
        }

        final CredentialDefinitionRecord credDefRecord =
                new CredentialDefinitionRecord(
                        normalizedId,
                        normalizedIssuerId,
                        normalizedSchemaId,
                        normalizedType,
                        normalizedTag,
                        publicMetadata,
                        signerDid);

        credDefRecord.setCreatedAt(txTimestamp.toString());

        credDefRegistry.put(normalizedId, credDefRecord);
        eventEmitter.emit(
                VdrEvents.CRED_DEF_CREATED,
                Map.of(
                        "id",
                        normalizedId,
                        "issuerId",
                        normalizedIssuerId,
                        "schemaId",
                        normalizedSchemaId));
        return credDefRecord;
    }

    /**
     * Resolves a credential definition by identifier.
     *
     * @param id credential definition identifier
     * @return resolved credential definition record
     * @throws VdrException if the identifier is blank or record is missing
     */
    public CredentialDefinitionRecord getCredDef(final String id) {
        final String normalizedId = validationService.requireNotBlank(id, "id");
        final CredentialDefinitionRecord credDefRecord = credDefRegistry.get(normalizedId);
        if (credDefRecord == null) {
            throw VdrException.notFound("creddef", normalizedId);
        }
        return credDefRecord;
    }

    /**
     * Lists all credential definition entries from ledger namespace.
     *
     * @return immutable-style registry projections containing id and value
     */
    public List<RegistryEntry<CredentialDefinitionRecord>> listCredDefs() {
        return credDefRegistry.listAll();
    }
}
