package com.example.vdr.contract;

import com.example.vdr.context.SsiContext;
import com.example.vdr.exception.VdrException;
import com.example.vdr.ledger.RegistryEntry;
import com.example.vdr.model.CredentialDefinitionRecord;
import com.example.vdr.model.CredentialRecord;
import com.example.vdr.model.DidDocument;
import com.example.vdr.model.SchemaRecord;
import com.example.vdr.util.JsonUtil;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.function.Supplier;
import org.hyperledger.fabric.contract.Context;
import org.hyperledger.fabric.contract.ContractInterface;
import org.hyperledger.fabric.contract.annotation.Contract;
import org.hyperledger.fabric.contract.annotation.Transaction;
import org.hyperledger.fabric.shim.ChaincodeException;
import org.hyperledger.fabric.shim.ChaincodeStub;

/**
 * Layer 1: Contract API. This class serves as the entry point for all Hyperledger Fabric
 * transactions. It is responsible for routing transaction requests, performing basic
 * string-to-object conversions, and handling JSON serialization/deserialization.
 *
 * <p>Following the VDR 4-tier architecture, this layer contains NO business logic or direct ledger
 * I/O; instead, it delegates to {@link com.example.vdr.logic.DidService} and other service classes
 * via {@link com.example.vdr.context.SsiContext}.
 */
@Contract(name = "VdrContract")
public class VdrContract implements ContractInterface {

    /**
     * Creates a custom transaction context for SSI operations. Overriding this method allows us to
     * provide a specialized {@link SsiContext} that initializes registries and services for each
     * transaction.
     *
     * @param stub The Fabric ChaincodeStub used to interact with the ledger.
     * @return A new instance of {@link SsiContext}.
     */
    @Override
    public Context createContext(final ChaincodeStub stub) {
        return new SsiContext(stub);
    }

    /**
     * Registers a new Decentralized Identifier (DID) document on the ledger. This transaction
     * validates the input, ensures the DID does not already exist, and assigns ownership to the
     * transaction caller.
     *
     * @param ctx The custom SSI transaction context.
     * @param id The unique identifier for the DID.
     * @param didDocumentJson The raw JSON representation of the DID document.
     * @return The canonical JSON representation of the created {@link DidDocument}.
     * @throws ChaincodeException If validation fails or the DID already exists.
     */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String createDid(final SsiContext ctx, final String id, final String didDocumentJson) {
        return execute(
                () -> {
                    final DidDocument document =
                            ctx.getServices().getDidService().createDid(id, didDocumentJson);
                    return JsonUtil.toCanonicalJson(document);
                });
    }

    /**
     * Retrieves an existing DID document from the ledger by its ID. This is a read-only transaction
     * (EVALUATE).
     *
     * @param ctx The custom SSI transaction context.
     * @param id The unique identifier of the DID to retrieve.
     * @return The canonical JSON representation of the {@link DidDocument}.
     * @throws ChaincodeException If the DID document is not found.
     */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String getDid(final SsiContext ctx, final String id) {
        return execute(
                () -> JsonUtil.toCanonicalJson(ctx.getServices().getDidService().getDid(id)));
    }

    /**
     * Deactivates a DID document, marking it as INACTIVE on the ledger. This operation follows the
     * SSI principle of an append-only ledger by using soft-deletion. Only the owner of the DID
     * document can perform this action.
     *
     * @param ctx The custom SSI transaction context.
     * @param id The unique identifier of the DID to deactivate.
     * @param jwt The JWT token containing the issuer claim for authentication and authorization.
     * @return The canonical JSON representation of the updated {@link DidDocument}.
     * @throws ChaincodeException If the caller is not the owner or the DID is not found.
     */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String deactivateDid(final SsiContext ctx, final String id, final String jwt) {
        return execute(
                () ->
                        JsonUtil.toCanonicalJson(
                                ctx.getServices()
                                        .getDidService()
                                        .deactivateDid(requireSignerDid(ctx, jwt), id)));
    }

    /**
     * Updates an existing DID document when it is still active and owned by the caller.
     *
     * @param ctx The custom SSI transaction context.
     * @param id The unique identifier of the DID to update.
     * @param didDocumentJson The replacement DID document JSON payload.
     * @param jwt The JWT token containing the issuer claim for authentication and authorization.
     * @return The canonical JSON representation of the updated {@link DidDocument}.
     * @throws ChaincodeException If validation or authorization fails.
     */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String updateDid(
            final SsiContext ctx, final String id, final String didDocumentJson, final String jwt) {
        return execute(
                () ->
                        JsonUtil.toCanonicalJson(
                                ctx.getServices()
                                        .getDidService()
                                        .updateDid(
                                                requireSignerDid(ctx, jwt), id, didDocumentJson)));
    }

    /**
     * Lists all registered DID documents on the ledger. Iterates through all entries stored under
     * the DID composite key namespace.
     *
     * @param ctx The custom SSI transaction context.
     * @return A JSON array containing all DID registry entries.
     */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String listDids(final SsiContext ctx) {
        return execute(() -> toEntryList(ctx.getServices().getDidService().listDids()));
    }

    /**
     * Registers a new SSI Schema on the ledger. Schemas define the structure of credentials. This
     * method validates the schema structure and associates it with an issuer DID.
     *
     * @param ctx The custom SSI transaction context.
     * @param id The unique identifier for the schema.
     * @param issuerId The DID of the issuer owning this schema.
     * @param name The human-readable name of the schema.
     * @param version The version string of the schema.
     * @param attrNamesJson A JSON array of attribute names defined in the schema.
     * @param jwt The JWT token containing the issuer claim for authentication and authorization.
     * @return The canonical JSON representation of the created {@link SchemaRecord}.
     * @throws ChaincodeException If validation fails or the schema ID is duplicated.
     */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String createSchema(
            final SsiContext ctx,
            final String id,
            final String issuerId,
            final String name,
            final String version,
            final String attrNamesJson,
            final String jwt) {
        return execute(
                () -> {
                    final SchemaRecord schemaRecord =
                            ctx.getServices()
                                    .getSchemaService()
                                    .createSchema(
                                            requireSignerDid(ctx, jwt),
                                            id,
                                            issuerId,
                                            name,
                                            version,
                                            attrNamesJson);
                    return JsonUtil.toCanonicalJson(schemaRecord);
                });
    }

    /**
     * Retrieves an existing Schema from the ledger by its ID.
     *
     * @param ctx The custom SSI transaction context.
     * @param id The unique identifier of the schema to retrieve.
     * @return The canonical JSON representation of the {@link SchemaRecord}.
     * @throws ChaincodeException If the schema is not found.
     */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String getSchema(final SsiContext ctx, final String id) {
        return execute(
                () -> JsonUtil.toCanonicalJson(ctx.getServices().getSchemaService().getSchema(id)));
    }

    /**
     * Lists all registered Schemas on the ledger.
     *
     * @param ctx The custom SSI transaction context.
     * @return A JSON array containing all schema registry entries.
     */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String listSchemas(final SsiContext ctx) {
        return execute(() -> toEntryList(ctx.getServices().getSchemaService().listSchemas()));
    }

    /**
     * Deactivates a Schema, marking it as INACTIVE. Only the original owner/creator of the schema
     * can deactivate it.
     *
     * @param ctx The custom SSI transaction context.
     * @param id The unique identifier of the schema to deactivate.
     * @param jwt The JWT token containing the issuer claim for authentication and authorization.
     * @return The canonical JSON representation of the updated {@link SchemaRecord}.
     * @throws ChaincodeException If the caller is not the owner or the schema is missing.
     */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String deactivateSchema(final SsiContext ctx, final String id, final String jwt) {
        return execute(
                () ->
                        JsonUtil.toCanonicalJson(
                                ctx.getServices()
                                        .getSchemaService()
                                        .deactivateSchema(requireSignerDid(ctx, jwt), id)));
    }

    /**
     * Creates a new Credential Definition on the ledger. A credential definition describes the
     * structure and details of a verifiable credential. It is linked to a schema and an issuer DID,
     * and owned by the transaction caller.
     *
     * @param ctx The custom SSI transaction context.
     * @param id The unique identifier for the credential definition.
     * @param issuerId The DID of the issuer who owns this credential definition.
     * @param schemaId The ID of the schema this credential definition adheres to.
     * @param type The type of credential definition (e.g., "CL" for Camenisch-Lysyanskaya).
     * @param tag An arbitrary tag for the credential definition.
     * @param publicMetadataJson Optional public metadata associated with the credential definition
     *     as a JSON string.
     * @param jwt The JWT token containing the issuer claim for authentication and authorization.
     * @return The canonical JSON representation of the created {@link CredentialDefinitionRecord}.
     * @throws ChaincodeException If validation fails, the schema or issuer DID is not active, or
     *     the ID is duplicated.
     */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String createCredDef(
            final SsiContext ctx,
            final String id,
            final String issuerId,
            final String schemaId,
            final String type,
            final String tag,
            final String publicMetadataJson,
            final String jwt) {
        return execute(
                () -> {
                    final CredentialDefinitionRecord credDefRecord =
                            ctx.getServices()
                                    .getCredentialDefinitionService()
                                    .createCredDef(
                                            requireSignerDid(ctx, jwt),
                                            id,
                                            issuerId,
                                            schemaId,
                                            type,
                                            tag,
                                            publicMetadataJson);
                    return JsonUtil.toCanonicalJson(credDefRecord);
                });
    }

    /**
     * Retrieves an existing Credential Definition from the ledger by its ID.
     *
     * @param ctx The custom SSI transaction context.
     * @param id The unique identifier of the credential definition to retrieve.
     * @return The canonical JSON representation of the {@link CredentialDefinitionRecord}.
     * @throws ChaincodeException If the credential definition is not found.
     */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String getCredDef(final SsiContext ctx, final String id) {
        return execute(
                () ->
                        JsonUtil.toCanonicalJson(
                                ctx.getServices().getCredentialDefinitionService().getCredDef(id)));
    }

    /**
     * Lists all registered Credential Definitions on the ledger.
     *
     * @param ctx The custom SSI transaction context.
     * @return A JSON array containing all credential definition registry entries.
     */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String listCredDefs(final SsiContext ctx) {
        return execute(
                () ->
                        toEntryList(
                                ctx.getServices().getCredentialDefinitionService().listCredDefs()));
    }

    /**
     * Deactivates a Credential Definition, marking it as INACTIVE. Only the original owner/creator
     * of the credential definition can deactivate it.
     *
     * @param ctx The custom SSI transaction context.
     * @param id The unique identifier of the credential definition to deactivate.
     * @param jwt The JWT token containing the issuer claim for authentication and authorization.
     * @return The canonical JSON representation of the updated {@link CredentialDefinitionRecord}.
     * @throws ChaincodeException If the caller is not the owner or the credential definition is
     *     missing.
     */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String deactivateCredDef(final SsiContext ctx, final String id, final String jwt) {
        return execute(
                () ->
                        JsonUtil.toCanonicalJson(
                                ctx.getServices()
                                        .getCredentialDefinitionService()
                                        .deactivateCredDef(requireSignerDid(ctx, jwt), id)));
    }

    /**
     * Issues a new verifiable credential, recording its hash and metadata on the ledger. This
     * transaction links the credential to a credential definition and a subject DID. The full
     * credential object is NOT stored on-ledger due to privacy and size constraints; only a
     * cryptographically verifiable hash and essential metadata are recorded.
     *
     * @param ctx The custom SSI transaction context.
     * @param credentialId The unique identifier for this specific credential instance.
     * @param credDefId The ID of the credential definition used to issue this credential.
     * @param subjectDid The DID of the subject to whom this credential is issued.
     * @param credentialJson The full credential object as a JSON string. This will be hashed and
     *     only the hash will be stored on-ledger.
     * @param jwt The JWT token containing the issuer claim for authentication and authorization.
     * @return A JSON object containing the credential ID, hash, issuance date, and a message.
     * @throws ChaincodeException If validation fails, the credential definition or subject DID is
     *     not active, or the credential ID is duplicated.
     */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String issueCredential(
            final SsiContext ctx,
            final String credentialId,
            final String credDefId,
            final String subjectDid,
            final String credentialJson,
            final String jwt) {
        return execute(
                () -> {
                    final CredentialRecord credentialRecord =
                            ctx.getServices()
                                    .getCredentialService()
                                    .issueCredential(
                                            requireSignerDid(ctx, jwt),
                                            ctx.getTxTimestamp(),
                                            credentialId,
                                            credDefId,
                                            subjectDid,
                                            credentialJson);

                    final ObjectNode result = JsonUtil.objectNode();
                    result.put("credentialId", credentialRecord.getId());
                    result.put("credentialHash", credentialRecord.getCredentialHash());
                    result.put("issuanceDate", credentialRecord.getIssuanceDate());
                    result.put(
                            "message",
                            "Credential issued successfully. Store the full credential object offline.");
                    return JsonUtil.toCanonicalJson(result);
                });
    }

    /**
     * Verifies a provided credential against its on-ledger hash and schema rules. This transaction
     * checks if the hash of the {@code providedCredentialJson} matches the hash stored for {@code
     * credentialId} and validates the credential's structure against its associated schema.
     *
     * @param ctx The custom SSI transaction context.
     * @param credentialId The unique identifier of the credential to verify.
     * @param providedCredentialJson The full credential object provided by the holder for
     *     verification.
     * @return A JSON object indicating verification status, credential ID, issuance date, subject
     *     DID, and a verification message.
     * @throws ChaincodeException If verification fails due to hash mismatch, invalid schema, or the
     *     credential being revoked/not found.
     */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String verifyCredential(
            final SsiContext ctx, final String credentialId, final String providedCredentialJson) {
        return execute(
                () -> {
                    final CredentialRecord credentialRecord =
                            ctx.getServices()
                                    .getCredentialService()
                                    .verifyCredential(credentialId, providedCredentialJson);

                    final ObjectNode result = JsonUtil.objectNode();
                    result.put("credentialId", credentialRecord.getId());
                    result.put("verified", true);
                    result.put("issuanceDate", credentialRecord.getIssuanceDate());
                    result.put("subjectDid", credentialRecord.getSubjectDid());
                    result.put(
                            "message",
                            "Credential verified: content is authentic and matches schema");
                    return JsonUtil.toCanonicalJson(result);
                });
    }

    /**
     * Revokes an issued credential, marking its status as REVOKED on the ledger. This action
     * follows the append-only ledger principle via soft-deletion. Only the original issuer (owner)
     * of the credential can revoke it.
     *
     * @param ctx The custom SSI transaction context.
     * @param credentialId The unique identifier of the credential to revoke.
     * @param jwt The JWT token containing the issuer claim for authentication and authorization.
     * @return The canonical JSON representation of the updated {@link CredentialRecord}.
     * @throws ChaincodeException If the caller is not the issuer/owner, or the credential is not
     *     found.
     */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String revokeCredential(
            final SsiContext ctx, final String credentialId, final String jwt) {
        return execute(
                () ->
                        JsonUtil.toCanonicalJson(
                                ctx.getServices()
                                        .getCredentialService()
                                        .revokeCredential(
                                                requireSignerDid(ctx, jwt), credentialId)));
    }

    /**
     * Validates JWT and returns authenticated signer DID for submit transactions.
     *
     * @param ctx transaction context with scoped service graph
     * @param jwt compact JWT token
     * @return signer DID resolved from validated token
     */
    private String requireSignerDid(final SsiContext ctx, final String jwt) {
        return ctx.getServices()
                .getJwtValidator()
                .validate(jwt, ctx.getTxTimestamp())
                .getSignerDid();
    }

    /**
     * Retrieves the metadata of an issued credential from the ledger by its ID. Note that this only
     * returns the on-ledger metadata (hash, status, dates), not the full credential content, which
     * is stored off-ledger.
     *
     * @param ctx The custom SSI transaction context.
     * @param credentialId The unique identifier of the credential to retrieve.
     * @return The canonical JSON representation of the {@link CredentialRecord} metadata.
     * @throws ChaincodeException If the credential is not found.
     */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String getCredential(final SsiContext ctx, final String credentialId) {
        return execute(
                () ->
                        JsonUtil.toCanonicalJson(
                                ctx.getServices()
                                        .getCredentialService()
                                        .getCredential(credentialId)));
    }

    /**
     * Lists all issued credentials on the ledger. This transaction iterates through all entries
     * stored under the credential composite key namespace.
     *
     * @param ctx The custom SSI transaction context.
     * @return A JSON array containing all credential registry entries (metadata only).
     */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String listCredentials(final SsiContext ctx) {
        return execute(
                () -> toEntryList(ctx.getServices().getCredentialService().listCredentials()));
    }

    /**
     * Converts a list of {@link RegistryEntry} objects into a canonical JSON array string. Each
     * entry in the resulting JSON array will contain an "id" field and a "value" field representing
     * the serialized domain object.
     *
     * @param <T> The type of the domain object held within the {@link RegistryEntry}.
     * @param entries The list of {@link RegistryEntry} objects to convert.
     * @return A canonical JSON array string of the entries.
     */
    private <T> String toEntryList(final List<RegistryEntry<T>> entries) {
        final ArrayNode results = JsonUtil.arrayNode();
        for (final RegistryEntry<T> entry : entries) {
            final ObjectNode item = JsonUtil.objectNode();
            item.put("id", entry.getId());
            item.set("value", JsonUtil.readTree(JsonUtil.toCanonicalJson(entry.getValue())));
            results.add(item);
        }
        return JsonUtil.toCanonicalJson(results);
    }

    /**
     * Executes a given action and wraps any {@link VdrException} into a Fabric {@link
     * ChaincodeException}. This ensures that business logic exceptions are properly translated into
     * Fabric-specific error codes and messages, preventing raw exceptions from propagating.
     *
     * @param <T> The return type of the action.
     * @param action The supplier function encapsulating the business logic to execute.
     * @return The result of the action.
     * @throws ChaincodeException If a {@link VdrException} occurs during execution.
     */
    private <T> T execute(final Supplier<T> action) {
        try {
            return action.get();
        } catch (final VdrException exception) {
            throw new ChaincodeException(exception.getMessage(), exception.getErrorCode());
        }
    }
}
