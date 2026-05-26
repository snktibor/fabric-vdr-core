package com.example.vdr.e2e.entity;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.example.vdr.e2e.support.CryptoUtils;
import com.example.vdr.e2e.support.ScenarioContext;
import com.example.vdr.e2e.support.TransactionExecutor;
import java.security.KeyPair;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles the issuance, query, verification and revocation lifecycle of credentials. A credential
 * depends on an Issuer DID, a Credential Definition (which itself depends on a Schema) and a
 * Subject DID; this handler implicitly provisions any prerequisite that is not already present in
 * the {@link ScenarioContext}, mirroring the behaviour of {@link CredDefEntityHandler}.
 */
public class CredentialEntityHandler extends AbstractEntityHandler {

    public static final String TYPE = "credential";

    private static final String DEFAULT_CRED_DEF_SEED = "credential-default-creddef";
    private static final String DEFAULT_SUBJECT_DID_SEED = "credential-default-subject-did";

    /**
     * Mock credential payload. Attributes match the schema attribute set produced by {@link
     * SchemaEntityHandler#submitCreate} ({@code attr1}, {@code attr2}).
     */
    private static final String MOCK_CREDENTIAL_JSON =
            "{\"attr1\":\"alpha-value\",\"attr2\":\"beta-value\"}";

    private static final String TAMPERED_CREDENTIAL_JSON =
            "{\"attr1\":\"alpha-value\",\"attr2\":\"tampered-value\"}";

    private final DidEntityHandler didHandler;
    private final CredDefEntityHandler credDefHandler;

    /**
     * Per-scenario cache of the canonical credential payload used at issuance, keyed by the
     * effective credential id. Required because verification must replay the exact JSON that was
     * hashed on-ledger.
     */
    private final Map<String, String> credentialPayloadsByCredId = new HashMap<>();

    public CredentialEntityHandler(
            final TransactionExecutor executor,
            final ScenarioContext context,
            final DidEntityHandler didHandler,
            final CredDefEntityHandler credDefHandler) {
        super(executor, context);
        this.didHandler = didHandler;
        this.credDefHandler = credDefHandler;
    }

    @Override
    public String typeKey() {
        return TYPE;
    }

    @Override
    protected String getterTxName() {
        return "getCredential";
    }

    @Override
    protected String listTxName() {
        return "listCredentials";
    }

    @Override
    public void create(final String rawId) {
        final String credDefId = ensureCredDef();
        final String issuerDid = context.requireOwner(credDefId);
        final String subjectDid = ensureSubjectDid();

        final String credentialId = uniqueId(rawId);
        context.setEffectiveId(rawId, credentialId);
        context.recordOwner(credentialId, issuerDid);
        credentialPayloadsByCredId.put(credentialId, MOCK_CREDENTIAL_JSON);

        final KeyPair issuerKeyPair = context.requireDidKeyPair(issuerDid);
        final String jwt = CryptoUtils.buildDidJwt(issuerDid, issuerKeyPair);
        executor.submitWithJwt(
                "issueCredential", jwt, credentialId, credDefId, subjectDid, MOCK_CREDENTIAL_JSON);
    }

    @Override
    public void existingOnLedger(final String rawId) {
        create(rawId);
        if (context.lastTransactionException() != null) {
            fail(
                    "Setup failed: could not issue prerequisite credential. Error: "
                            + context.lastTransactionException().getMessage());
        }
    }

    /**
     * Revokes the credential using its owning Issuer's JWT. Routed through the dedicated step in
     * {@link com.example.vdr.e2e.steps.CredentialSteps}; deliberately not mapped to {@link
     * #deactivate(String)} because the chaincode endpoint and resulting status are
     * revocation-specific ({@code REVOKED} rather than {@code INACTIVE}).
     */
    public void revoke(final String rawId) {
        final String credentialId = resolveId(rawId);
        final String issuerDid = context.requireOwner(credentialId);
        final KeyPair keyPair = context.requireDidKeyPair(issuerDid);
        final String jwt = CryptoUtils.buildDidJwt(issuerDid, keyPair);
        executor.submitWithJwt("revokeCredential", jwt, credentialId);
    }

    public void revokeWithInvalidJwt(final String rawId) {
        final String credentialId = resolveId(rawId);
        executor.submitWithJwt("revokeCredential", "not-a-valid-jwt", credentialId);
    }

    /** Evaluates {@code verifyCredential} with the original payload captured at issuance. */
    public void verify(final String rawId) {
        verifyWith(rawId, requirePayload(rawId));
    }

    /** Evaluates {@code verifyCredential} with a deliberately tampered payload. */
    public void verifyWithTamperedPayload(final String rawId) {
        verifyWith(rawId, TAMPERED_CREDENTIAL_JSON);
    }

    private void verifyWith(final String rawId, final String payload) {
        final String credentialId = resolveId(rawId);
        context.setLastQueryResponse(null);
        context.setLastTransactionException(null);
        try {
            context.setLastQueryResponse(
                    executor.evaluate("verifyCredential", credentialId, payload));
        } catch (Exception e) {
            context.setLastTransactionException(e);
        }
    }

    @Override
    public void assertStatus(final String rawId, final String expectedStatus) {
        final String credentialId = resolveId(rawId);
        try {
            final String response = executor.evaluate("getCredential", credentialId);
            final String expected = "\"status\":\"" + expectedStatus.toUpperCase() + "\"";
            assertTrue(
                    response.contains(expected),
                    () ->
                            String.format(
                                    "Expected credential '%s' to have status '%s', got: %s",
                                    credentialId, expectedStatus.toUpperCase(), response));
        } catch (Exception e) {
            fail("Failed to query credential status for '" + credentialId + "': " + e.getMessage());
        }
    }

    private String requirePayload(final String rawId) {
        final String credentialId = resolveId(rawId);
        final String payload = credentialPayloadsByCredId.get(credentialId);
        if (payload == null) {
            throw new IllegalStateException(
                    "No issuance payload recorded for credential '"
                            + credentialId
                            + "'. Did you issue the credential before verifying?");
        }
        return payload;
    }

    private String ensureCredDef() {
        if (!context.hasEffectiveId(DEFAULT_CRED_DEF_SEED)) {
            credDefHandler.create(DEFAULT_CRED_DEF_SEED);
            if (context.lastTransactionException() != null) {
                fail(
                        "Could not create implicit credential definition: "
                                + context.lastTransactionException().getMessage());
            }
        }
        return context.resolveId(DEFAULT_CRED_DEF_SEED);
    }

    private String ensureSubjectDid() {
        if (!context.hasEffectiveId(DEFAULT_SUBJECT_DID_SEED)) {
            didHandler.create(DEFAULT_SUBJECT_DID_SEED);
            if (context.lastTransactionException() != null) {
                fail(
                        "Could not create implicit subject DID: "
                                + context.lastTransactionException().getMessage());
            }
        }
        return context.resolveId(DEFAULT_SUBJECT_DID_SEED);
    }
}
