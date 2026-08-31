package com.example.vdr.logic;

import com.example.vdr.exception.VdrException;
import com.example.vdr.ledger.ChaincodeEventEmitter;
import com.example.vdr.ledger.RegistryEntry;
import com.example.vdr.ledger.SchemaRegistry;
import com.example.vdr.ledger.VdrEvents;
import com.example.vdr.model.SchemaRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Layer-3 business service for schema publication and lookup.
 *
 * <p>This service validates schema creation inputs and enforces issuer DID activity while
 * delegating persistence to the schema registry layer. Published schemas are immutable per the
 * AnonCreds specification: no update or deactivation transitions exist.
 */
public class SchemaService {

    private final SchemaRegistry schemaRegistry;
    private final DidService didService;
    private final ValidationService validationService;
    private final ChaincodeEventEmitter eventEmitter;
    private final Instant txTimestamp;

    /**
     * Creates a schema service with required registries and cross-cutting utilities.
     *
     * @param schemaRegistry registry used for schema persistence
     * @param didService DID service enforcing issuer DID existence and active status
     * @param validationService fail-fast validation utility
     * @param eventEmitter chaincode event emitter for state-change notifications
     * @param txTimestamp client-declared transaction time used for audit timestamps
     */
    public SchemaService(
            final SchemaRegistry schemaRegistry,
            final DidService didService,
            final ValidationService validationService,
            final ChaincodeEventEmitter eventEmitter,
            final Instant txTimestamp) {
        this.schemaRegistry = schemaRegistry;
        this.didService = didService;
        this.validationService = validationService;
        this.eventEmitter = eventEmitter;
        this.txTimestamp = txTimestamp;
    }

    /**
     * Creates and stores a new schema record owned by the caller.
     *
     * <p>Creation enforces that the schema id is a DID URL rooted at the declared issuer DID,
     * enforces id uniqueness, validates issuer DID existence/activity, and normalizes schema
     * attributes before persistence.
     *
     * @param signerDid DID resolved from verified JWT issuer claim
     * @param id unique schema identifier as a DID URL rooted at the issuer DID
     * @param issuerId issuer DID that governs the schema
     * @param name schema display name
     * @param version schema version identifier
     * @param attrNamesJson JSON array of schema attribute names
     * @return created and persisted schema record
     * @throws VdrException if validation fails, issuer is missing/inactive, or schema id already
     *     exists
     */
    public SchemaRecord createSchema(
            final String signerDid,
            final String id,
            final String issuerId,
            final String name,
            final String version,
            final String attrNamesJson) {
        // The issuer is validated first because the schema id must be rooted at it.
        final String normalizedIssuerId = validationService.requireValidDid(issuerId, "issuerId");
        final String normalizedId =
                validationService.requireValidObjectId(
                        id, normalizedIssuerId, AnonCredsObjectTypes.SCHEMA, "id");
        final String normalizedName = validationService.requireNotBlank(name, "name");
        final String normalizedVersion = validationService.requireNotBlank(version, "version");
        final List<String> attrNames =
                validationService.parseStringArray(attrNamesJson, "attrNames");

        if (!normalizedIssuerId.equals(signerDid)) {
            throw VdrException.unauthorized(
                    "Unauthorized: signer DID must match issuerId for schema creation");
        }

        if (schemaRegistry.exists(normalizedId)) {
            throw VdrException.duplicate("schema", normalizedId);
        }

        didService.assertDidActive(normalizedIssuerId);

        final SchemaRecord schemaRecord =
                new SchemaRecord(
                        normalizedId,
                        normalizedIssuerId,
                        normalizedName,
                        normalizedVersion,
                        attrNames,
                        signerDid);

        schemaRecord.setCreatedAt(txTimestamp.toString());

        schemaRegistry.put(normalizedId, schemaRecord);
        eventEmitter.emit(
                VdrEvents.SCHEMA_CREATED,
                Map.of("id", normalizedId, "issuerId", normalizedIssuerId));
        return schemaRecord;
    }

    /**
     * Resolves a schema record by identifier.
     *
     * @param id schema identifier
     * @return resolved schema record
     * @throws VdrException if identifier is blank or schema is not found
     */
    public SchemaRecord getSchema(final String id) {
        final String normalizedId = validationService.requireNotBlank(id, "id");
        final SchemaRecord schemaRecord = schemaRegistry.get(normalizedId);
        if (schemaRecord == null) {
            throw VdrException.notFound("schema", normalizedId);
        }
        return schemaRecord;
    }

    /**
     * Lists all schema entries from ledger namespace.
     *
     * @return registry entry list containing schema ids and values
     */
    public List<RegistryEntry<SchemaRecord>> listSchemas() {
        return schemaRegistry.listAll();
    }
}
