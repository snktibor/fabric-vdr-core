package com.example.vdr.e2e.entity;

import static org.junit.jupiter.api.Assertions.fail;

import com.example.vdr.e2e.support.CryptoUtils;
import com.example.vdr.e2e.support.ScenarioContext;
import com.example.vdr.e2e.support.TransactionExecutor;
import java.security.KeyPair;

public class CredDefEntityHandler extends AbstractEntityHandler {

    public static final String TYPE = "credential definition";
    private static final String DEFAULT_ISSUER_DID_SEED = "issuer-did";
    private static final String DEFAULT_SCHEMA_SEED = "schema:vdr:creddef-default";

    private final DidEntityHandler didHandler;
    private final SchemaEntityHandler schemaHandler;

    public CredDefEntityHandler(
            final TransactionExecutor executor,
            final ScenarioContext context,
            final DidEntityHandler didHandler,
            final SchemaEntityHandler schemaHandler) {
        super(executor, context);
        this.didHandler = didHandler;
        this.schemaHandler = schemaHandler;
    }

    @Override
    public String typeKey() {
        return TYPE;
    }

    @Override
    protected String getterTxName() {
        return "getCredDef";
    }

    @Override
    protected String listTxName() {
        return "listCredDefs";
    }

    @Override
    public void create(final String rawId) {
        final String issuerDid = ensureIssuerDid();
        final String schemaId = ensureSchema();
        final String credDefId = uniqueId(rawId);
        context.setEffectiveId(rawId, credDefId);
        context.recordOwner(credDefId, issuerDid);

        final KeyPair keyPair = context.requireDidKeyPair(issuerDid);
        final String jwt = CryptoUtils.buildDidJwt(issuerDid, keyPair);
        executor.submitWithJwt(
                "createCredDef", jwt, credDefId, issuerDid, schemaId, "CL", "tag-1", "{}");
    }

    @Override
    public void existingOnLedger(final String rawId) {
        create(rawId);
        if (context.lastTransactionException() != null) {
            fail(
                    "Setup failed: could not create prerequisite credential definition. Error: "
                            + context.lastTransactionException().getMessage());
        }
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

    private String ensureSchema() {
        if (!context.hasEffectiveId(DEFAULT_SCHEMA_SEED)) {
            schemaHandler.create(DEFAULT_SCHEMA_SEED);
            if (context.lastTransactionException() != null) {
                fail(
                        "Could not create implicit schema for cred def: "
                                + context.lastTransactionException().getMessage());
            }
        }
        return context.resolveId(DEFAULT_SCHEMA_SEED);
    }
}
