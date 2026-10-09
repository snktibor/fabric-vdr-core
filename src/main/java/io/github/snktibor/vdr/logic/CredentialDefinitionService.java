package io.github.snktibor.vdr.logic;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.snktibor.vdr.exception.VdrException;
import io.github.snktibor.vdr.ledger.ChaincodeEventEmitter;
import io.github.snktibor.vdr.ledger.CredentialDefinitionRegistry;
import io.github.snktibor.vdr.ledger.RegistryEntry;
import io.github.snktibor.vdr.ledger.SchemaRegistry;
import io.github.snktibor.vdr.ledger.VdrEvents;
import io.github.snktibor.vdr.model.CredentialDefinitionRecord;
import io.github.snktibor.vdr.model.SchemaRecord;
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
    private final DidService didService;
    private final AuthorizationService authorizationService;
    private final ValidationService validationService;
    private final ChaincodeEventEmitter eventEmitter;
    private final Instant txTimestamp;

    /**
     * Creates a credential definition service with its required dependencies.
     *
     * @param credDefRegistry registry used to persist credential definitions
     * @param schemaRegistry registry used to resolve referenced schemas
     * @param didService DID service enforcing issuer DID existence and active status
     * @param authorizationService owner authorization utility
     * @param validationService fail-fast input and JSON validation utility
     * @param eventEmitter chaincode event emitter for state-change notifications
     * @param txTimestamp client-declared transaction time used for audit timestamps
     */
    public CredentialDefinitionService(
            final CredentialDefinitionRegistry credDefRegistry,
            final SchemaRegistry schemaRegistry,
            final DidService didService,
            final AuthorizationService authorizationService,
            final ValidationService validationService,
            final ChaincodeEventEmitter eventEmitter,
            final Instant txTimestamp) {
        this.credDefRegistry = credDefRegistry;
        this.schemaRegistry = schemaRegistry;
        this.didService = didService;
        this.authorizationService = authorizationService;
        this.validationService = validationService;
        this.eventEmitter = eventEmitter;
        this.txTimestamp = txTimestamp;
    }

    /**
     * Creates and stores a new credential definition owned by the caller.
     *
     * <p>The method enforces deterministic preconditions before persistence: required input fields,
     * an id that is a DID URL rooted at an issuer DID, id uniqueness, referenced schema existence,
     * and active issuer DID status.
     *
     * @param signerDid DID resolved from verified JWT issuer claim
     * @param id unique credential definition identifier as a DID URL rooted at the issuer DID
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
            final String schemaId,
            final String type,
            final String tag,
            final String publicMetadataJson) {
        // The id is the sole source of the issuer: a credential definition is published under the
        // DID its own identifier is rooted at.
        final String normalizedId =
                validationService.requireValidObjectId(
                        id, AnonCredsObjectTypes.PUBLIC_CRED_DEF, "id");
        final String issuerDid = validationService.requireIssuerDidOf(normalizedId, "id");
        final String normalizedSchemaId = validationService.requireNotBlank(schemaId, "schemaId");
        final String normalizedType =
                validationService.requireAllowedType(type, VdrTypes.CRED_DEF_TYPES, "type");
        final String normalizedTag = validationService.requireNotBlank(tag, "tag");
        final JsonNode publicMetadata =
                validationService.parseJsonObject(publicMetadataJson, "publicMetadata");

        authorizationService.assertSignerOwnsEntity(signerDid, issuerDid, "creddef", normalizedId);

        if (credDefRegistry.exists(normalizedId)) {
            throw VdrException.duplicate("creddef", normalizedId);
        }

        final SchemaRecord schemaRecord = schemaRegistry.get(normalizedSchemaId);
        if (schemaRecord == null) {
            throw VdrException.notFound("schema", normalizedSchemaId);
        }

        didService.assertDidActive(issuerDid);

        final CredentialDefinitionRecord credDefRecord =
                new CredentialDefinitionRecord(
                        normalizedId,
                        issuerDid,
                        normalizedSchemaId,
                        normalizedType,
                        normalizedTag,
                        publicMetadata,
                        signerDid);

        credDefRecord.setCreatedAt(txTimestamp.toString());

        credDefRegistry.put(normalizedId, credDefRecord);
        eventEmitter.emit(
                VdrEvents.CRED_DEF_CREATED,
                Map.of("id", normalizedId, "issuerId", issuerDid, "schemaId", normalizedSchemaId));
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
