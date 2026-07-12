package com.example.vdr.e2e.entity;

import static org.junit.jupiter.api.Assertions.fail;

import com.example.vdr.e2e.support.ScenarioContext;
import com.example.vdr.e2e.support.TransactionExecutor;
import java.security.KeyPair;

public class RevRegDefEntityHandler extends AbstractEntityHandler {

    public static final String TYPE = "revocation registry definition";
    private static final String DEFAULT_ISSUER_DID_SEED = "issuer-did";
    private static final String DEFAULT_CRED_DEF_SEED = "creddef:vdr:revregdef-default";

    private final DidEntityHandler didHandler;
    private final CredDefEntityHandler credDefHandler;

    public RevRegDefEntityHandler(
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
        return "getRevRegDef";
    }

    @Override
    protected String listTxName() {
        return "listRevRegDefs";
    }

    @Override
    public void create(final String rawId) {
        final String issuerDid = ensureIssuerDid();
        final String credDefId = ensureCredDef();
        final String revRegDefId = uniqueId(rawId);
        context.setEffectiveId(rawId, revRegDefId);
        context.recordOwner(revRegDefId, issuerDid);

        final KeyPair keyPair = context.requireDidKeyPair(issuerDid);
        executor.submitSigned(
                "createRevRegDef",
                issuerDid,
                keyPair,
                revRegDefId,
                issuerDid,
                credDefId,
                "ALLOSAUR",
                "{}",
                "1000");
    }

    @Override
    public void existingOnLedger(final String rawId) {
        create(rawId);
        if (context.lastTransactionException() != null) {
            fail(
                    "Setup failed: could not create prerequisite revocation registry definition."
                            + " Error: "
                            + context.lastTransactionException().getMessage());
        }
    }

    private String ensureIssuerDid() {
        if (context.heldSigningDid() != null) {
            return context.heldSigningDid();
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

    private String ensureCredDef() {
        if (!context.hasEffectiveId(DEFAULT_CRED_DEF_SEED)) {
            credDefHandler.create(DEFAULT_CRED_DEF_SEED);
            if (context.lastTransactionException() != null) {
                fail(
                        "Could not create implicit credential definition for rev reg def: "
                                + context.lastTransactionException().getMessage());
            }
        }
        return context.resolveId(DEFAULT_CRED_DEF_SEED);
    }
}
