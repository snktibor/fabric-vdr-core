package com.example.vdr.e2e.entity;

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
    public void create(final String rawId) {
        final String issuerDid = ensureIssuerDid();
        final String schemaId = uniqueId(rawId);
        context.setEffectiveId(TYPE, schemaId);

        final KeyPair keyPair = context.requireDidKeyPair(issuerDid);
        final String jwt = CryptoUtils.buildDidJwt(issuerDid, keyPair);
        executor.submitWithJwt(
                "createSchema",
                jwt,
                schemaId,
                issuerDid,
                "Test Schema",
                "1.0",
                "[\"attr1\", \"attr2\"]");
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
        final String schemaId = uniqueId(rawId);
        context.setEffectiveId(TYPE, schemaId);
        executor.submitWithJwt(
                "createSchema",
                jwt,
                schemaId,
                issuerDid,
                "Test Schema",
                "1.0",
                "[\"attr1\", \"attr2\"]");
    }

    private String ensureIssuerDid() {
        final String existing = context.resolveId(DidEntityHandler.TYPE, null);
        if (existing != null) {
            return existing;
        }
        didHandler.create(DEFAULT_ISSUER_DID_SEED);
        if (context.lastTransactionException() != null) {
            fail(
                    "Could not create implicit issuer DID: "
                            + context.lastTransactionException().getMessage());
        }
        return context.resolveId(DidEntityHandler.TYPE, DEFAULT_ISSUER_DID_SEED);
    }
}
