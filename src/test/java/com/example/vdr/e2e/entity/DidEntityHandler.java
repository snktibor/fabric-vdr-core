package com.example.vdr.e2e.entity;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.example.vdr.e2e.support.CryptoUtils;
import com.example.vdr.e2e.support.ScenarioContext;
import com.example.vdr.e2e.support.TransactionExecutor;
import java.security.KeyPair;

public class DidEntityHandler extends AbstractEntityHandler {

    public static final String TYPE = "DID document";

    public DidEntityHandler(final TransactionExecutor executor, final ScenarioContext context) {
        super(executor, context);
    }

    @Override
    public String typeKey() {
        return TYPE;
    }

    @Override
    protected String getterTxName() {
        return "getDid";
    }

    @Override
    protected String listTxName() {
        return "listDids";
    }

    @Override
    public void create(final String rawId) {
        final String did = uniqueId(rawId);
        context.setEffectiveId(rawId, did);
        final KeyPair keyPair;
        try {
            keyPair = CryptoUtils.generateP256KeyPair();
        } catch (Exception e) {
            context.setLastTransactionException(e);
            return;
        }
        context.didKeys().put(did, keyPair);
        executor.submit("createDid", did, CryptoUtils.buildDidJson(did, keyPair, null));
    }

    /** Setup variant: creating the prerequisite must not silently fail the scenario. */
    @Override
    public void existingOnLedger(final String rawId) {
        create(rawId);
        if (context.lastTransactionException() != null) {
            fail(
                    "Setup failed: could not create prerequisite DID document. Error: "
                            + context.lastTransactionException().getMessage());
        }
    }

    @Override
    public void update(final String rawId) {
        final String did = resolveId(rawId);
        final KeyPair keyPair = context.requireDidKeyPair(did);
        final String updatedJson =
                CryptoUtils.buildDidJson(
                        did, keyPair, "https://example.com/updated/" + System.currentTimeMillis());
        executor.submitSigned("updateDid", did, keyPair, did, updatedJson);
    }

    @Override
    public void deactivate(final String rawId) {
        final String did = resolveId(rawId);
        final KeyPair keyPair = context.requireDidKeyPair(did);
        executor.submitSigned("deactivateDid", did, keyPair, did);
    }

    @Override
    public void deactivateWithInvalidJwt(final String rawId) {
        final String did = resolveId(rawId);
        executor.submitWithJwt("deactivateDid", "not-a-valid-jwt", did);
    }

    @Override
    public void assertStatus(final String rawId, final String expectedStatus) {
        final String did = resolveId(rawId);
        try {
            final String response = executor.evaluate("getDid", did);
            // JSON key "status" is case-sensitive (lowercase); the value follows chaincode
            // upper-case.
            final String expectedField = "\"status\":\"" + expectedStatus.toUpperCase() + "\"";
            assertTrue(
                    response.contains(expectedField),
                    () ->
                            String.format(
                                    "Expected DID '%s' to have status '%s', but response was: %s",
                                    did, expectedStatus.toUpperCase(), response));
        } catch (Exception e) {
            fail("Failed to query DID status for '" + did + "': " + e.getMessage());
        }
    }
}
