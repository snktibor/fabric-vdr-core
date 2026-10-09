package io.github.snktibor.vdr.e2e.entity;

import static org.junit.jupiter.api.Assertions.fail;

import io.github.snktibor.vdr.e2e.support.ScenarioContext;
import io.github.snktibor.vdr.e2e.support.TransactionExecutor;
import io.github.snktibor.vdr.logic.AnonCredsObjectTypes;
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
        submitCreate(rawId, ensureIssuerDid());
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

    /**
     * Creates a schema signed with the key held via {@link ScenarioContext#heldSigningDid()}.
     *
     * <p>Tokens are scope-bound to a single invocation, so the scenario holds the signing identity
     * rather than a pre-minted JWT; the token is minted at submission for this exact create.
     */
    public void createWithHeldKey(final String rawId) {
        final String issuerDid = context.heldSigningDid();
        if (issuerDid == null) {
            throw new IllegalStateException(
                    "No held signing key in scenario context; precede this step with"
                            + " 'Given I hold the signing key for the DID ...'.");
        }
        submitCreate(rawId, issuerDid);
    }

    private void submitCreate(final String rawId, final String issuerDid) {
        final String schemaId = mintObjectId(issuerDid, AnonCredsObjectTypes.SCHEMA);
        context.setEffectiveId(rawId, schemaId);
        context.recordOwner(schemaId, issuerDid);
        final KeyPair keyPair = context.requireDidKeyPair(issuerDid);
        executor.submitSigned(
                "createSchema",
                issuerDid,
                keyPair,
                schemaId,
                "Test Schema",
                "1.0",
                "[\"attr1\", \"attr2\"]");
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
}
