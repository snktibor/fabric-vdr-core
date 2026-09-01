package com.example.vdr.logic;

import com.example.vdr.exception.VdrException;
import com.example.vdr.ledger.ChaincodeEventEmitter;
import com.example.vdr.ledger.CredentialDefinitionRegistry;
import com.example.vdr.ledger.RegistryEntry;
import com.example.vdr.ledger.RevRegDefRegistry;
import com.example.vdr.ledger.VdrEvents;
import com.example.vdr.model.CredentialDefinitionRecord;
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
 * Published revocation registry definitions follow the tails-free VB20 accumulator revocation
 * scheme (Vitto–Biryukov, eprint 2020/777, as implemented by the AnonCreds v2 reference) and are
 * immutable once created: no update or deactivation transitions exist.
 */
public class RevRegDefService {

    private final RevRegDefRegistry revRegDefRegistry;
    private final CredentialDefinitionRegistry credDefRegistry;
    private final DidService didService;
    private final AuthorizationService authorizationService;
    private final ValidationService validationService;
    private final ChaincodeEventEmitter eventEmitter;
    private final Instant txTimestamp;

    /**
     * Creates a revocation registry definition service with its required dependencies.
     *
     * @param revRegDefRegistry registry used to persist revocation registry definitions
     * @param credDefRegistry registry used to resolve referenced credential definitions
     * @param didService DID service enforcing issuer DID existence and active status
     * @param authorizationService owner authorization utility
     * @param validationService fail-fast input and JSON validation utility
     * @param eventEmitter chaincode event emitter for state-change notifications
     * @param txTimestamp client-declared transaction time used for audit timestamps
     */
    public RevRegDefService(
            final RevRegDefRegistry revRegDefRegistry,
            final CredentialDefinitionRegistry credDefRegistry,
            final DidService didService,
            final AuthorizationService authorizationService,
            final ValidationService validationService,
            final ChaincodeEventEmitter eventEmitter,
            final Instant txTimestamp) {
        this.revRegDefRegistry = revRegDefRegistry;
        this.credDefRegistry = credDefRegistry;
        this.didService = didService;
        this.authorizationService = authorizationService;
        this.validationService = validationService;
        this.eventEmitter = eventEmitter;
        this.txTimestamp = txTimestamp;
    }

    /**
     * Creates and stores a new revocation registry definition owned by the caller.
     *
     * <p>The method enforces deterministic preconditions before persistence: required input fields,
     * an id that is a DID URL rooted at an issuer DID, id uniqueness, referenced credential
     * definition existence, issuer consistency with the referenced credential definition, and
     * active issuer DID status. The {@code signerDid} argument is resolved from the verified
     * DID-JWT upstream by the contract layer; no token verification happens here.
     *
     * @param signerDid DID resolved from verified JWT issuer claim
     * @param id unique revocation registry definition identifier as a DID URL rooted at the issuer
     *     DID
     * @param credDefId referenced credential definition identifier
     * @param type revocation scheme identifier; must be allowlisted (see {@link
     *     VdrTypes#REV_REG_DEF_TYPES})
     * @param publicKeysJson public revocation keys JSON object for the definition
     * @param maxCredNum maximum number of credentials the registry can track
     * @return created and persisted revocation registry definition record
     * @throws VdrException if validation fails, dependencies are missing, issuer is inactive or
     *     inconsistent with the referenced credential definition, or id already exists
     */
    public RevRegDefRecord createRevRegDef(
            final String signerDid,
            final String id,
            final String credDefId,
            final String type,
            final String publicKeysJson,
            final long maxCredNum) {
        // The id is the sole source of the issuer: a definition is published under the DID its own
        // identifier is rooted at.
        final String normalizedId =
                validationService.requireValidObjectId(id, AnonCredsObjectTypes.REV_REG_DEF, "id");
        final String issuerDid = validationService.requireIssuerDidOf(normalizedId, "id");
        final String normalizedCredDefId =
                validationService.requireNotBlank(credDefId, "credDefId");
        final String normalizedType =
                validationService.requireAllowedType(type, VdrTypes.REV_REG_DEF_TYPES, "type");
        final JsonNode publicKeys =
                validationService.requireRevocationPublicKeys(publicKeysJson, "publicKeys");
        if (maxCredNum <= 0) {
            throw VdrException.invalid("maxCredNum must be greater than zero");
        }

        authorizationService.assertSignerOwnsEntity(
                signerDid, issuerDid, "revregdef", normalizedId);

        if (revRegDefRegistry.exists(normalizedId)) {
            throw VdrException.duplicate("revregdef", normalizedId);
        }

        final CredentialDefinitionRecord credDefRecord = credDefRegistry.get(normalizedCredDefId);
        if (credDefRecord == null) {
            throw VdrException.notFound("creddef", normalizedCredDefId);
        }
        if (!issuerDid.equals(credDefRecord.getIssuerId())) {
            throw VdrException.invalid(
                    "issuer '"
                            + issuerDid
                            + "' does not match issuer of referenced creddef '"
                            + normalizedCredDefId
                            + "'");
        }

        didService.assertDidActive(issuerDid);

        final RevRegDefRecord revRegDefRecord =
                new RevRegDefRecord(
                        normalizedId,
                        issuerDid,
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
                        issuerDid,
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
