package com.example.vdr.logic;

import com.example.vdr.exception.VdrException;
import com.example.vdr.ledger.CredentialDefinitionRegistry;
import com.example.vdr.ledger.CredentialRegistry;
import com.example.vdr.ledger.DidRegistry;
import com.example.vdr.ledger.RegistryEntry;
import com.example.vdr.ledger.SchemaRegistry;
import com.example.vdr.model.CredentialDefinitionRecord;
import com.example.vdr.model.CredentialRecord;
import com.example.vdr.model.DidDocument;
import com.example.vdr.model.RecordStatus;
import com.example.vdr.model.SchemaRecord;
import com.example.vdr.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/**
 * Layer-3 business service for credential issuance, verification, and revocation.
 *
 * <p>The service enforces SSI invariants such as active dependencies, canonical credential hashing,
 * schema-constrained payload validation, and owner-based revocation while delegating ledger
 * persistence to layer-4 registries.
 */
public class CredentialService {

    private final CredentialRegistry credentialRegistry;
    private final CredentialDefinitionRegistry credDefRegistry;
    private final SchemaRegistry schemaRegistry;
    private final DidRegistry didRegistry;
    private final AuthorizationService authorizationService;
    private final ValidationService validationService;

    /**
     * Creates a credential service with transaction-scoped dependencies.
     *
     * @param credentialRegistry registry used to persist credential records
     * @param credDefRegistry registry used to resolve credential definitions
     * @param schemaRegistry registry used to resolve schemas
     * @param didRegistry registry used to resolve subject DIDs
     * @param authorizationService owner authorization utility
     * @param validationService fail-fast validation utility
     */
    public CredentialService(
            final CredentialRegistry credentialRegistry,
            final CredentialDefinitionRegistry credDefRegistry,
            final SchemaRegistry schemaRegistry,
            final DidRegistry didRegistry,
            final AuthorizationService authorizationService,
            final ValidationService validationService) {
        this.credentialRegistry = credentialRegistry;
        this.credDefRegistry = credDefRegistry;
        this.schemaRegistry = schemaRegistry;
        this.didRegistry = didRegistry;
        this.authorizationService = authorizationService;
        this.validationService = validationService;
    }

    /**
     * Issues a credential record by storing canonical hash and metadata on ledger.
     *
     * <p>Full credential content remains off-ledger. Only a deterministic hash and verification
     * metadata are recorded to support integrity checks without disclosing sensitive credential
     * payloads.
     *
     * @param callerId identity of issuer transaction invoker
     * @param issuedAt issuance timestamp used for immutable record metadata
     * @param credentialId unique credential identifier
     * @param credDefId credential definition identifier that governs issuance
     * @param subjectDid subject DID bound to the issued credential
     * @param credentialJson credential payload as JSON object
     * @return created and persisted credential record
     * @throws VdrException if inputs are invalid, referenced entities are missing/inactive, schema
     *     validation fails, or id exists
     */
    public CredentialRecord issueCredential(
            final String callerId,
            final Instant issuedAt,
            final String credentialId,
            final String credDefId,
            final String subjectDid,
            final String credentialJson) {
        final String normalizedCredentialId =
                validationService.requireNotBlank(credentialId, "credentialId");
        final String normalizedCredDefId =
                validationService.requireNotBlank(credDefId, "credDefId");
        final String normalizedSubjectDid =
                validationService.requireNotBlank(subjectDid, "subjectDid");
        final JsonNode credentialNode =
                validationService.parseJsonObject(credentialJson, "credential");

        if (credentialRegistry.exists(normalizedCredentialId)) {
            throw VdrException.duplicate("credential", normalizedCredentialId);
        }

        final CredentialDefinitionRecord credDef = credDefRegistry.get(normalizedCredDefId);
        if (credDef == null) {
            throw VdrException.notFound("creddef", normalizedCredDefId);
        }
        if (credDef.getStatus() != RecordStatus.ACTIVE) {
            throw VdrException.invalid("creddef '" + normalizedCredDefId + "' is not active");
        }

        final SchemaRecord schemaRecord = schemaRegistry.get(credDef.getSchemaId());
        if (schemaRecord == null) {
            throw VdrException.notFound("schema", credDef.getSchemaId());
        }
        if (schemaRecord.getStatus() != RecordStatus.ACTIVE) {
            throw VdrException.invalid("schema '" + credDef.getSchemaId() + "' is not active");
        }

        validationService.validateCredentialAgainstSchema(credentialNode, schemaRecord);

        final DidDocument subject = didRegistry.get(normalizedSubjectDid);
        if (subject == null) {
            throw VdrException.notFound("subject DID", normalizedSubjectDid);
        }
        if (subject.getStatus() != RecordStatus.ACTIVE) {
            throw VdrException.invalid("subject DID '" + normalizedSubjectDid + "' is not active");
        }

        final String canonicalCredential = JsonUtil.toCanonicalJson(credentialNode);
        final String credentialHash = JsonUtil.sha256(canonicalCredential);
        final long issuanceDate = issuedAt.toEpochMilli();

        final CredentialRecord credentialRecord =
                new CredentialRecord(
                        normalizedCredentialId,
                        normalizedCredDefId,
                        normalizedSubjectDid,
                        credentialHash,
                        issuanceDate,
                        RecordStatus.ACTIVE,
                        callerId);

        credentialRegistry.put(normalizedCredentialId, credentialRecord);
        return credentialRecord;
    }

    /**
     * Verifies a provided credential payload against ledger metadata.
     *
     * <p>Verification checks revoked status, canonical hash equality, and schema conformance based
     * on the referenced credential definition.
     *
     * @param credentialId identifier of the credential metadata record
     * @param providedCredentialJson credential payload supplied for verification
     * @return existing credential record when verification succeeds
     * @throws VdrException if record is missing/revoked, hash mismatches, or schema/dependency
     *     validation fails
     */
    public CredentialRecord verifyCredential(
            final String credentialId, final String providedCredentialJson) {
        final String normalizedCredentialId =
                validationService.requireNotBlank(credentialId, "credentialId");
        final CredentialRecord credentialRecord = credentialRegistry.get(normalizedCredentialId);
        if (credentialRecord == null) {
            throw VdrException.notFound("credential", normalizedCredentialId);
        }
        if (credentialRecord.getStatus() == RecordStatus.REVOKED) {
            throw VdrException.invalid("credential '" + normalizedCredentialId + "' is revoked");
        }

        final JsonNode providedCredentialNode =
                validationService.parseJsonObject(providedCredentialJson, "providedCredential");
        final String canonicalProvided = JsonUtil.toCanonicalJson(providedCredentialNode);
        final String providedHash = JsonUtil.sha256(canonicalProvided);

        if (!providedHash.equals(credentialRecord.getCredentialHash())) {
            throw VdrException.invalid(
                    "Credential hash mismatch: credential content has been modified");
        }

        final CredentialDefinitionRecord credDef =
                credDefRegistry.get(credentialRecord.getCredDefId());
        if (credDef == null) {
            throw VdrException.notFound("creddef", credentialRecord.getCredDefId());
        }

        final SchemaRecord schemaRecord = schemaRegistry.get(credDef.getSchemaId());
        if (schemaRecord == null) {
            throw VdrException.notFound("schema", credDef.getSchemaId());
        }

        validationService.validateCredentialAgainstSchema(providedCredentialNode, schemaRecord);
        return credentialRecord;
    }

    /**
     * Retrieves credential metadata by identifier.
     *
     * @param credentialId credential identifier
     * @return stored credential record metadata
     * @throws VdrException if identifier is blank or record is not found
     */
    public CredentialRecord getCredential(final String credentialId) {
        final String normalizedCredentialId =
                validationService.requireNotBlank(credentialId, "credentialId");
        final CredentialRecord credentialRecord = credentialRegistry.get(normalizedCredentialId);
        if (credentialRecord == null) {
            throw VdrException.notFound("credential", normalizedCredentialId);
        }
        return credentialRecord;
    }

    /**
     * Lists all credential records stored in the credential namespace.
     *
     * @return registry entry list containing credential ids and values
     */
    public List<RegistryEntry<CredentialRecord>> listCredentials() {
        return credentialRegistry.listAll();
    }

    /**
     * Performs append-only revocation of a credential record.
     *
     * <p>The caller must own the credential. Revocation updates status to {@code REVOKED} while
     * preserving historical ledger state.
     *
     * @param callerId identity of transaction invoker
     * @param credentialId credential identifier to revoke
     * @return updated credential record with revoked status
     * @throws VdrException if credential is missing or caller is not owner
     */
    public CredentialRecord revokeCredential(final String callerId, final String credentialId) {
        final CredentialRecord credentialRecord = getCredential(credentialId);
        authorizationService.assertCallerIsOwner(
                callerId, credentialRecord.getOwnerId(), "credential", credentialRecord.getId());
        credentialRecord.setStatus(RecordStatus.REVOKED);
        credentialRegistry.put(credentialRecord.getId(), credentialRecord);
        return credentialRecord;
    }
}
