package com.example.vdr.e2e.entity;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.example.vdr.e2e.support.CryptoUtils;
import com.example.vdr.e2e.support.ScenarioContext;
import com.example.vdr.e2e.support.TransactionExecutor;
import java.security.KeyPair;

public class SchemaEntityHandler extends AbstractEntityHandler {

    public static final String TYPE = "schema";
    private static final String DEFAULT_ISSUER_DID_SEED = "issuer-did";

    private final DidEntityHandler didHandler;

    public SchemaEntityHandler(
            final TransactionExecutor executor,
            final ScenarioContext context,
            final DidEntityHandler didHandler) {
        super(executor, context);
        this.didHandler = didHandler;
    }

    @Override
    public String typeKey() {
        return TYPE;
    }

    @Override
    protected String getterTxName() {
        return "getSchema";
    }

    @Override
    protected String listTxName() {
        return "listSchemas";
    }

    @Override
    public void create(final String rawId) {
        final String issuerDid = ensureIssuerDid();
        submitCreate(rawId, issuerDid, signWithIssuer(issuerDid));
    }

    @Override
    public void existingOnLedger(final String rawId) {
        create(rawId);
        if (context.lastTransactionException() != null) {
            fail(
                    "Setup failed: could not create prerequisite schema. Error: "
                            + context.lastTransactionException().getMessage());
        }
    }

    /** Creates a schema using a JWT pre-issued and stored in {@link ScenarioContext#heldJwt()}. */
    public void createWithHeldJwt(final String rawId) {
        final String issuerDid = context.heldJwtSubjectDid();
        final String jwt = context.heldJwt();
        if (jwt == null || issuerDid == null) {
            throw new IllegalStateException(
                    "No held JWT in scenario context; precede this step with"
                            + " 'Given I hold a valid JWT for the DID ...'.");
        }
        submitCreate(rawId, issuerDid, jwt);
    }

    @Override
    public void deactivate(final String rawId) {
        final String schemaId = resolveId(rawId);
        final String ownerDid = context.requireOwner(schemaId);
        final KeyPair keyPair = context.requireDidKeyPair(ownerDid);
        final String jwt = CryptoUtils.buildDidJwt(ownerDid, keyPair);
        executor.submitWithJwt("deactivateSchema", jwt, schemaId);
    }

    @Override
    public void deactivateWithInvalidJwt(final String rawId) {
        final String schemaId = resolveId(rawId);
        executor.submitWithJwt("deactivateSchema", "not-a-valid-jwt", schemaId);
    }

    @Override
    public void assertStatus(final String rawId, final String expectedStatus) {
        final String schemaId = resolveId(rawId);
        try {
            final String response = executor.evaluate("getSchema", schemaId);
            final String expected = "\"status\":\"" + expectedStatus.toUpperCase() + "\"";
            assertTrue(
                    response.contains(expected),
                    () ->
                            String.format(
                                    "Expected schema '%s' to have status '%s', got: %s",
                                    schemaId, expectedStatus.toUpperCase(), response));
        } catch (Exception e) {
            fail("Failed to query schema status for '" + schemaId + "': " + e.getMessage());
        }
    }

    private void submitCreate(final String rawId, final String issuerDid, final String jwt) {
        final String schemaId = uniqueId(rawId);
        context.setEffectiveId(rawId, schemaId);
        context.recordOwner(schemaId, issuerDid);
        executor.submitWithJwt(
                "createSchema",
                jwt,
                schemaId,
                issuerDid,
                "Test Schema",
                "1.0",
                "[\"attr1\", \"attr2\"]");
    }

    private String signWithIssuer(final String issuerDid) {
        final KeyPair keyPair = context.requireDidKeyPair(issuerDid);
        return CryptoUtils.buildDidJwt(issuerDid, keyPair);
    }

    private String ensureIssuerDid() {
        if (context.heldJwtSubjectDid() != null) {
            return context.heldJwtSubjectDid();
        }
        if (!context.hasEffectiveId(DEFAULT_ISSUER_DID_SEED)) {
            didHandler.create(DEFAULT_ISSUER_DID_SEED);
            if (context.lastTransactionException() != null) {
                fail(
                        "Could not create implicit issuer DID: "
                                + context.lastTransactionException().getMessage());
            }
        }
        return context.resolveId(DEFAULT_ISSUER_DID_SEED);
    }
}
