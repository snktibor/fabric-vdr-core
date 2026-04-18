package com.example.vdr.logic;

import com.example.vdr.exception.VdrException;
import com.example.vdr.ledger.DidRegistry;
import com.example.vdr.ledger.RegistryEntry;
import com.example.vdr.ledger.SchemaRegistry;
import com.example.vdr.model.DidDocument;
import com.example.vdr.model.RecordStatus;
import com.example.vdr.model.SchemaRecord;
import java.util.List;

/**
 * Layer-3 business service for schema lifecycle and ownership enforcement.
 *
 * <p>This service validates schema creation inputs, enforces issuer DID activity, and performs
 * append-only status transitions while delegating persistence to the schema registry layer.
 */
public class SchemaService {

    private final SchemaRegistry schemaRegistry;
    private final DidRegistry didRegistry;
    private final AuthorizationService authorizationService;
    private final ValidationService validationService;

    /**
     * Creates a schema service with required registries and cross-cutting utilities.
     *
     * @param schemaRegistry registry used for schema persistence
     * @param didRegistry registry used to resolve issuer DIDs
     * @param authorizationService owner authorization utility
     * @param validationService fail-fast validation utility
     */
    public SchemaService(
            final SchemaRegistry schemaRegistry,
            final DidRegistry didRegistry,
            final AuthorizationService authorizationService,
            final ValidationService validationService) {
        this.schemaRegistry = schemaRegistry;
        this.didRegistry = didRegistry;
        this.authorizationService = authorizationService;
        this.validationService = validationService;
    }

    /**
     * Creates and stores a new schema record owned by the caller.
     *
     * <p>Creation enforces id uniqueness, validates issuer DID existence/activity, and normalizes
     * schema attributes before persistence.
     *
     * @param callerId identity of transaction invoker becoming owner
     * @param id unique schema identifier
     * @param issuerId issuer DID that governs the schema
     * @param name schema display name
     * @param version schema version identifier
     * @param attrNamesJson JSON array of schema attribute names
     * @return created and persisted schema record
     * @throws VdrException if validation fails, issuer is missing/inactive, or schema id already
     *     exists
     */
    public SchemaRecord createSchema(
            final String callerId,
            final String id,
            final String issuerId,
            final String name,
            final String version,
            final String attrNamesJson) {
        final String normalizedId = validationService.requireNotBlank(id, "id");
        final String normalizedIssuerId = validationService.requireNotBlank(issuerId, "issuerId");
        final String normalizedName = validationService.requireNotBlank(name, "name");
        final String normalizedVersion = validationService.requireNotBlank(version, "version");
        final List<String> attrNames =
                validationService.parseStringArray(attrNamesJson, "attrNames");

        if (schemaRegistry.exists(normalizedId)) {
            throw VdrException.duplicate("schema", normalizedId);
        }

        final DidDocument issuer = didRegistry.get(normalizedIssuerId);
        if (issuer == null) {
            throw VdrException.notFound("issuer DID", normalizedIssuerId);
        }
        if (issuer.getStatus() != RecordStatus.ACTIVE) {
            throw VdrException.invalid("issuer DID '" + normalizedIssuerId + "' is not active");
        }

        final SchemaRecord schemaRecord =
                new SchemaRecord(
                        normalizedId,
                        normalizedIssuerId,
                        normalizedName,
                        normalizedVersion,
                        attrNames,
                        callerId);

        schemaRegistry.put(normalizedId, schemaRecord);
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

    /**
     * Performs append-only soft deactivation of a schema.
     *
     * <p>Ownership is enforced before status transition to preserve authorization guarantees while
     * retaining historical ledger state.
     *
     * @param callerId identity of transaction invoker
     * @param id schema identifier to deactivate
     * @return updated schema record with inactive status
     * @throws VdrException if schema is missing or caller is not the owner
     */
    public SchemaRecord deactivateSchema(final String callerId, final String id) {
        final SchemaRecord schemaRecord = getSchema(id);
        authorizationService.assertCallerIsOwner(
                callerId, schemaRecord.getOwnerId(), "schema", schemaRecord.getId());
        schemaRecord.setStatus(RecordStatus.INACTIVE);
        schemaRegistry.put(schemaRecord.getId(), schemaRecord);
        return schemaRecord;
    }

    /**
     * Enforces that caller is owner of the supplied schema record.
     *
     * @param callerId identity of transaction invoker
     * @param schemaRecord schema record subject to ownership check
     * @throws VdrException if caller does not own the schema
     */
    public void assertCallerOwnsSchema(final String callerId, final SchemaRecord schemaRecord) {
        authorizationService.assertCallerIsOwner(
                callerId, schemaRecord.getOwnerId(), "schema", schemaRecord.getId());
    }
}
