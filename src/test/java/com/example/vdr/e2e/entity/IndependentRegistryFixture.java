package com.example.vdr.e2e.entity;

import static org.junit.jupiter.api.Assertions.fail;

import com.example.vdr.TestVectors;
import com.example.vdr.e2e.support.ScenarioContext;
import com.example.vdr.e2e.support.TransactionExecutor;
import com.example.vdr.logic.AnonCredsObjectTypes;
import com.example.vdr.logic.VdrTypes;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

/**
 * Provisions revocation registries that share nothing with each other.
 *
 * <p>The lifecycle handlers memoise one implicit issuer DID, schema, credential definition and
 * revocation registry definition per scenario, so every entry they create belongs to the same
 * registry. A cross-registry scenario needs the opposite: two owners that cannot authorise each
 * other's writes and two ledger keys that cannot collide. Since {@code createRevRegDef} requires
 * the definition's issuer to match the issuer of the referenced credential definition, independence
 * has to start at the DID and run through the whole chain — DID, schema, credential definition,
 * definition, genesis entry.
 *
 * <p>The handle passed in resolves to the definition id, matching how {@link
 * RevRegEntryEntityHandler} addresses an entry by its parent definition.
 */
public class IndependentRegistryFixture {

    private static final String SCHEMA_NAME = "Test Schema";
    private static final String SCHEMA_VERSION = "1.0";
    private static final String SCHEMA_ATTRIBUTES = "[\"attr1\", \"attr2\"]";
    private static final String CRED_DEF_TAG = "tag-1";
    private static final String GENESIS_ACCUM_VALUE = TestVectors.accumValue("accum-genesis");

    private final TransactionExecutor executor;
    private final ScenarioContext context;
    private final DidEntityHandler didHandler;
    private final List<String> provisionedHandles = new ArrayList<>();

    public IndependentRegistryFixture(
            final TransactionExecutor executor,
            final ScenarioContext context,
            final DidEntityHandler didHandler) {
        this.executor = executor;
        this.context = context;
        this.didHandler = didHandler;
    }

    /**
     * Creates a registry owned by a freshly minted issuer, up to and including its genesis entry.
     *
     * @param handle scenario-facing name; resolves to the definition id afterwards
     */
    public void provision(final String handle) {
        final String issuerSeed = handle + "-issuer";
        didHandler.create(issuerSeed);
        requireSuccess(handle, "issuer DID");
        final String issuerDid = context.resolveId(issuerSeed);
        final KeyPair keyPair = context.requireDidKeyPair(issuerDid);

        final String schemaId =
                AbstractEntityHandler.mintObjectId(issuerDid, AnonCredsObjectTypes.SCHEMA);
        executor.submitSigned(
                "createSchema",
                issuerDid,
                keyPair,
                schemaId,
                SCHEMA_NAME,
                SCHEMA_VERSION,
                SCHEMA_ATTRIBUTES);
        requireSuccess(handle, "schema");

        final String credDefId =
                AbstractEntityHandler.mintObjectId(issuerDid, AnonCredsObjectTypes.PUBLIC_CRED_DEF);
        executor.submitSigned(
                "createCredDef",
                issuerDid,
                keyPair,
                credDefId,
                schemaId,
                VdrTypes.CRED_DEF_BBS_V1,
                CRED_DEF_TAG,
                "{}");
        requireSuccess(handle, "credential definition");

        final String revRegDefId =
                AbstractEntityHandler.mintObjectId(issuerDid, AnonCredsObjectTypes.REV_REG_DEF);
        executor.submitSigned(
                "createRevRegDef",
                issuerDid,
                keyPair,
                revRegDefId,
                credDefId,
                VdrTypes.REV_REG_VB20_V1,
                TestVectors.REVOCATION_PUBLIC_KEYS_JSON,
                "1000");
        requireSuccess(handle, "revocation registry definition");

        executor.submitSigned(
                "createRevRegEntry", issuerDid, keyPair, revRegDefId, GENESIS_ACCUM_VALUE);
        requireSuccess(handle, "genesis entry");

        context.setEffectiveId(handle, revRegDefId);
        context.recordOwner(revRegDefId, issuerDid);
        provisionedHandles.add(handle);
    }

    /** Handles provisioned in this scenario, in provisioning order. */
    public List<String> provisionedHandles() {
        return List.copyOf(provisionedHandles);
    }

    /** The definition id a handle resolves to. */
    public String revRegDefId(final String handle) {
        if (!context.hasEffectiveId(handle)) {
            throw new IllegalStateException(
                    "No independent registry provisioned under handle '" + handle + "'.");
        }
        return context.resolveId(handle);
    }

    /** The issuer DID that owns the registry behind {@code handle}. */
    public String issuerDid(final String handle) {
        return context.requireOwner(revRegDefId(handle));
    }

    /** The signing key pair of the issuer that owns the registry behind {@code handle}. */
    public KeyPair issuerKeyPair(final String handle) {
        return context.requireDidKeyPair(issuerDid(handle));
    }

    private void requireSuccess(final String handle, final String stage) {
        if (context.lastTransactionException() != null) {
            fail(
                    "Setup failed: could not create the "
                            + stage
                            + " of independent registry '"
                            + handle
                            + "'. Error: "
                            + context.lastTransactionException().getMessage());
        }
    }
}
