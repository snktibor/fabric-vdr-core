package com.example.vdr.contract;

import com.example.vdr.context.SsiContext;
import com.example.vdr.exception.VdrException;
import com.example.vdr.ledger.KeyRevision;
import com.example.vdr.ledger.RegistryEntry;
import com.example.vdr.model.CredentialDefinitionRecord;
import com.example.vdr.model.DidDocument;
import com.example.vdr.model.RevRegDefRecord;
import com.example.vdr.model.RevRegEntryRecord;
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
     * validates the input, ensures the DID does not already exist, and records the DID itself as
     * its own owner — registration is open and ownership is self-assigned first come, first served.
     *
     * @param ctx The custom SSI transaction context.
     * @param id The unique identifier for the DID, in {@code did:<method>:<method-specific-id>}
     *     syntax; no DID method is privileged.
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
                                        .deactivateDid(
                                                requireSignerDid(ctx, jwt, "deactivateDid"), id)));
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
                                                requireSignerDid(ctx, jwt, "updateDid"),
                                                id,
                                                didDocumentJson)));
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
     * @param id The unique identifier for the schema: a DID URL rooted at the issuer's DID, which
     *     is the sole source of the issuer owning this schema (see {@link
     *     com.example.vdr.logic.ValidationService#requireValidObjectId}).
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
                                            requireSignerDid(ctx, jwt, "createSchema"),
                                            id,
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
     * Creates a new Credential Definition on the ledger. A credential definition describes the
     * structure and details of a verifiable credential. It is linked to a schema and an issuer DID,
     * with the signer DID recorded as owner.
     *
     * @param ctx The custom SSI transaction context.
     * @param id The unique identifier for the credential definition: a DID URL rooted at the
     *     issuer's DID, which is the sole source of the issuer who owns this credential definition
     *     (see {@link com.example.vdr.logic.ValidationService#requireValidObjectId}).
     * @param schemaId The ID of the schema this credential definition adheres to.
     * @param type The credential definition type; must be allowlisted (see {@link
     *     com.example.vdr.logic.VdrTypes#CRED_DEF_TYPES}).
     * @param tag An arbitrary tag for the credential definition.
     * @param publicMetadataJson Optional public metadata associated with the credential definition
     *     as a JSON string.
     * @param jwt The JWT token containing the issuer claim for authentication and authorization.
     * @return The canonical JSON representation of the created {@link CredentialDefinitionRecord}.
     * @throws ChaincodeException If validation fails, the schema or issuer DID is missing, the
     *     issuer DID is not active, or the ID is duplicated.
     */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String createCredDef(
            final SsiContext ctx,
            final String id,
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
                                            requireSignerDid(ctx, jwt, "createCredDef"),
                                            id,
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
     * Creates a new Revocation Registry Definition on the ledger. A revocation registry definition
     * publishes the parameters, capacity, and public cryptographic keys used to revoke credentials
     * under the tails-free AnonCreds v2 VB20 accumulator scheme. It is linked to a credential
     * definition and an issuer DID, with the signer DID recorded as owner, and strictly immutable
     * once created.
     *
     * @param ctx The custom SSI transaction context.
     * @param id The unique identifier for the revocation registry definition: a DID URL rooted at
     *     the issuer's DID, which is the sole source of the issuer who owns this revocation
     *     registry definition (see {@link
     *     com.example.vdr.logic.ValidationService#requireValidObjectId}).
     * @param credDefId The ID of the credential definition this registry revokes against.
     * @param type The revocation scheme identifier; must be allowlisted (see {@link
     *     com.example.vdr.logic.VdrTypes#REV_REG_DEF_TYPES}).
     * @param publicKeysJson The public revocation keys JSON object; exactly one field
     *     'revocationVerifyingKey' holding a compressed BLS12-381 G2 point as 192 lowercase hex
     *     characters.
     * @param maxCredNum The maximum number of credentials the registry can track.
     * @param jwt The JWT token containing the issuer claim for authentication and authorization.
     * @return The canonical JSON representation of the created {@link RevRegDefRecord}.
     * @throws ChaincodeException If validation fails, the credential definition or issuer DID is
     *     missing or inconsistent, the issuer DID is not active, or the ID is duplicated.
     */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String createRevRegDef(
            final SsiContext ctx,
            final String id,
            final String credDefId,
            final String type,
            final String publicKeysJson,
            final long maxCredNum,
            final String jwt) {
        return execute(
                () -> {
                    final RevRegDefRecord revRegDefRecord =
                            ctx.getServices()
                                    .getRevRegDefService()
                                    .createRevRegDef(
                                            requireSignerDid(ctx, jwt, "createRevRegDef"),
                                            id,
                                            credDefId,
                                            type,
                                            publicKeysJson,
                                            maxCredNum);
                    return JsonUtil.toCanonicalJson(revRegDefRecord);
                });
    }

    /**
     * Retrieves an existing Revocation Registry Definition from the ledger by its ID.
     *
     * @param ctx The custom SSI transaction context.
     * @param id The unique identifier of the revocation registry definition to retrieve.
     * @return The canonical JSON representation of the {@link RevRegDefRecord}.
     * @throws ChaincodeException If the revocation registry definition is not found.
     */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String getRevRegDef(final SsiContext ctx, final String id) {
        return execute(
                () ->
                        JsonUtil.toCanonicalJson(
                                ctx.getServices().getRevRegDefService().getRevRegDef(id)));
    }

    /**
     * Lists all registered Revocation Registry Definitions on the ledger.
     *
     * @param ctx The custom SSI transaction context.
     * @return A JSON array containing all revocation registry definition registry entries.
     */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String listRevRegDefs(final SsiContext ctx) {
        return execute(() -> toEntryList(ctx.getServices().getRevRegDefService().listRevRegDefs()));
    }

    /**
     * Creates the genesis Revocation Registry Entry for a revocation registry definition. The entry
     * stores the off-chain-computed VB20 accumulator value (a compressed BLS12-381 G1 point in
     * canonical hex form) — never a bit-string of revoked indices — and is the only mutable record
     * type in the VDR. Only the owner of the parent revocation registry definition may create it.
     *
     * @param ctx The custom SSI transaction context.
     * @param revRegDefId The ID of the parent revocation registry definition, which is also the
     *     entry's own identity: exactly one entry exists per definition.
     * @param accumValue The genesis accumulator value computed off chain by the issuer: a
     *     compressed BLS12-381 G1 point as exactly 96 lowercase hex characters.
     * @param jwt The JWT token containing the issuer claim for authentication and authorization.
     * @return The canonical JSON representation of the created {@link RevRegEntryRecord}.
     * @throws ChaincodeException If validation fails, the parent revocation registry definition is
     *     missing, the caller does not own it, or an entry already exists for it.
     */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String createRevRegEntry(
            final SsiContext ctx,
            final String revRegDefId,
            final String accumValue,
            final String jwt) {
        return execute(
                () -> {
                    final RevRegEntryRecord revRegEntryRecord =
                            ctx.getServices()
                                    .getRevRegEntryService()
                                    .createRevRegEntry(
                                            requireSignerDid(ctx, jwt, "createRevRegEntry"),
                                            revRegDefId,
                                            accumValue);
                    return JsonUtil.toCanonicalJson(revRegEntryRecord);
                });
    }

    /**
     * Replaces the accumulator value of an existing Revocation Registry Entry in place. Each
     * successful update increments the entry's audit version; concurrent submits against the same
     * key are serialized by Fabric MVCC validation. Only the owner of the parent revocation
     * registry definition may update the entry.
     *
     * @param ctx The custom SSI transaction context.
     * @param revRegDefId The ID of the parent revocation registry definition whose entry to update.
     * @param accumValue The replacement accumulator value computed off chain by the issuer: a
     *     compressed BLS12-381 G1 point as exactly 96 lowercase hex characters.
     * @param jwt The JWT token containing the issuer claim for authentication and authorization.
     * @return The canonical JSON representation of the updated {@link RevRegEntryRecord}.
     * @throws ChaincodeException If validation fails, the entry or its parent definition is
     *     missing, or the caller does not own the parent revocation registry definition.
     */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String updateRevRegEntry(
            final SsiContext ctx,
            final String revRegDefId,
            final String accumValue,
            final String jwt) {
        return execute(
                () ->
                        JsonUtil.toCanonicalJson(
                                ctx.getServices()
                                        .getRevRegEntryService()
                                        .updateRevRegEntry(
                                                requireSignerDid(ctx, jwt, "updateRevRegEntry"),
                                                revRegDefId,
                                                accumValue)));
    }

    /**
     * Retrieves the Revocation Registry Entry for a revocation registry definition.
     *
     * @param ctx The custom SSI transaction context.
     * @param revRegDefId The ID of the parent revocation registry definition whose entry to
     *     retrieve.
     * @return The canonical JSON representation of the {@link RevRegEntryRecord}.
     * @throws ChaincodeException If no entry exists for the revocation registry definition.
     */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String getRevRegEntry(final SsiContext ctx, final String revRegDefId) {
        return execute(
                () ->
                        JsonUtil.toCanonicalJson(
                                ctx.getServices()
                                        .getRevRegEntryService()
                                        .getRevRegEntry(revRegDefId)));
    }

    /**
     * Returns the accumulator revision history of a Revocation Registry Entry, oldest first. A
     * verifier can use this to resolve the accumulator value that was current at a given point in
     * time, since entry updates overwrite the value in place.
     *
     * <p>Every array element has the same shape, so a client binds against one schema: {@code
     * txId}, {@code timestamp} (the writing client's declared transaction time — audit metadata,
     * not attested), {@code deleted}, {@code accumValue} and {@code version}. Should a future
     * revision ever delete the entry, that revision carries {@code "deleted":true} and JSON {@code
     * null} for both record fields. An empty array means no entry was ever created for the
     * definition.
     *
     * @param ctx The custom SSI transaction context.
     * @param revRegDefId The ID of the parent revocation registry definition whose entry history to
     *     retrieve.
     * @return A canonical JSON array of revisions in chronological (oldest-first) order.
     */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String getRevRegEntryHistory(final SsiContext ctx, final String revRegDefId) {
        return execute(
                () -> {
                    final List<KeyRevision<RevRegEntryRecord>> revisions =
                            ctx.getServices()
                                    .getRevRegEntryService()
                                    .getRevRegEntryHistory(revRegDefId);
                    final ArrayNode results = JsonUtil.arrayNode();
                    for (final KeyRevision<RevRegEntryRecord> revision : revisions) {
                        final ObjectNode item = JsonUtil.objectNode();
                        item.put("txId", revision.getTxId());
                        item.put(
                                "timestamp",
                                revision.getTimestamp() == null
                                        ? null
                                        : revision.getTimestamp().toString());
                        item.put("deleted", revision.isDeleted());
                        if (revision.isDeleted()) {
                            item.putNull("accumValue");
                            item.putNull("version");
                        } else {
                            item.put("accumValue", revision.getValue().getAccumValue());
                            item.put("version", revision.getValue().getVersion());
                        }
                        results.add(item);
                    }
                    return JsonUtil.toCanonicalJson(results);
                });
    }

    /**
     * Validates JWT and returns authenticated signer DID for submit transactions.
     *
     * <p>The validator re-derives the token's transaction scope from the invocation itself: the
     * plain method name plus the submitted arguments read from the stub. Every JWT-gated
     * transaction takes the token as its final argument by convention; this is enforced here so the
     * scope digest is always computed over exactly the non-JWT arguments the client submitted.
     *
     * @param ctx transaction context with scoped service graph
     * @param jwt compact JWT token
     * @param method plain transaction method name being invoked (no contract-name prefix)
     * @return signer DID resolved from validated token
     */
    private String requireSignerDid(final SsiContext ctx, final String jwt, final String method) {
        final List<String> parameters = ctx.getStub().getParameters();
        final int lastIndex = parameters.size() - 1;
        if (lastIndex < 0 || !parameters.get(lastIndex).equals(jwt)) {
            throw VdrException.unauthorized(
                    "authorization failed: jwt must be the final transaction argument");
        }
        final List<String> scopeArgs = parameters.subList(0, lastIndex);
        return ctx.getServices()
                .getJwtValidator()
                .validate(jwt, ctx.getTxTimestamp(), method, scopeArgs)
                .getSignerDid();
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
