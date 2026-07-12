package com.example.vdr.e2e.entity;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.example.vdr.e2e.support.ScenarioContext;
import com.example.vdr.e2e.support.TransactionExecutor;
import java.security.KeyPair;

public class RevRegEntryEntityHandler extends AbstractEntityHandler {

    public static final String TYPE = "revocation registry entry";
    private static final String DEFAULT_REV_REG_DEF_SEED = "revregdef:vdr:revregentry-default";
    private static final String NON_OWNER_DID_SEED = "revregentry-non-owner-did";
    private static final String GENESIS_ACCUM_VALUE = "accum-genesis";
    private static final String UNAUTHORIZED_ACCUM_VALUE = "accum-unauthorized";

    private final DidEntityHandler didHandler;
    private final RevRegDefEntityHandler revRegDefHandler;

    public RevRegEntryEntityHandler(
            final TransactionExecutor executor,
            final ScenarioContext context,
            final DidEntityHandler didHandler,
            final RevRegDefEntityHandler revRegDefHandler) {
        super(executor, context);
        this.didHandler = didHandler;
        this.revRegDefHandler = revRegDefHandler;
    }

    @Override
    public String typeKey() {
        return TYPE;
    }

    @Override
    protected String getterTxName() {
        return "getRevRegEntry";
    }

    /**
     * The chaincode addresses an entry by its parent revocation registry definition id (one entry
     * per definition), so the parent id is recorded as the effective id for Gherkin resolution; the
     * entry's own UUIDv7 id is submitted at create time and preserved in the stored payload.
     */
    @Override
    public void create(final String rawId) {
        final String revRegDefId = ensureRevRegDef();
        final String revRegEntryId = uniqueId(rawId);
        context.setEffectiveId(rawId, revRegDefId);

        final String ownerDid = context.requireOwner(revRegDefId);
        final KeyPair keyPair = context.requireDidKeyPair(ownerDid);
        executor.submitSigned(
                "createRevRegEntry",
                ownerDid,
                keyPair,
                revRegEntryId,
                revRegDefId,
                GENESIS_ACCUM_VALUE);
    }

    @Override
    public void existingOnLedger(final String rawId) {
        create(rawId);
        if (context.lastTransactionException() != null) {
            fail(
                    "Setup failed: could not create prerequisite revocation registry entry."
                            + " Error: "
                            + context.lastTransactionException().getMessage());
        }
    }

    /** Submits an accumulator update signed by the recorded owner of the parent definition. */
    public void updateAccumulator(final String rawId, final String newAccumValue) {
        final String revRegDefId = resolveId(rawId);
        final String ownerDid = context.requireOwner(revRegDefId);
        final KeyPair keyPair = context.requireDidKeyPair(ownerDid);
        executor.submitSigned("updateRevRegEntry", ownerDid, keyPair, revRegDefId, newAccumValue);
    }

    /**
     * Submits an accumulator update signed by a validly registered, ACTIVE non-owner issuer DID so
     * the rejection provably occurs on the chaincode ownership check rather than on upstream JWT or
     * DID resolution. The token is minted through the regular signed path — fresh jti, correct
     * audience and a scope digest matching this exact invocation — so it clears every token-level
     * check and can only fail on ownership.
     */
    public void updateAccumulatorWithUnauthorizedActor(final String rawId) {
        final String revRegDefId = resolveId(rawId);
        final String nonOwnerDid = ensureNonOwnerDid();
        final KeyPair keyPair = context.requireDidKeyPair(nonOwnerDid);
        executor.submitSigned(
                "updateRevRegEntry", nonOwnerDid, keyPair, revRegDefId, UNAUTHORIZED_ACCUM_VALUE);
    }

    /** Evaluates the entry's accumulator history and stores the response for later assertions. */
    public void fetchHistory(final String rawId) {
        final String revRegDefId = resolveId(rawId);
        context.setLastQueryResponse(null);
        context.setLastTransactionException(null);
        try {
            context.setLastQueryResponse(executor.evaluate("getRevRegEntryHistory", revRegDefId));
        } catch (Exception e) {
            context.setLastTransactionException(e);
        }
    }

    /** Asserts the ledger entry currently carries the expected accumulator value and version. */
    public void assertAccumulator(
            final String rawId, final String expectedAccumValue, final long expectedVersion) {
        final String revRegDefId = resolveId(rawId);
        try {
            final String response = executor.evaluate(getterTxName(), revRegDefId);
            // JSON keys are case-sensitive; canonical serialization renders version as a number.
            final String expectedValueField = "\"accumValue\":\"" + expectedAccumValue + "\"";
            final String expectedVersionField = "\"version\":" + expectedVersion;
            assertTrue(
                    response.contains(expectedValueField)
                            && response.contains(expectedVersionField),
                    () ->
                            String.format(
                                    "Expected entry for '%s' to have accumValue '%s' and version"
                                            + " %d, but response was: %s",
                                    revRegDefId, expectedAccumValue, expectedVersion, response));
        } catch (Exception e) {
            fail(
                    "Failed to query revocation registry entry for '"
                            + revRegDefId
                            + "': "
                            + e.getMessage());
        }
    }

    private String ensureRevRegDef() {
        if (!context.hasEffectiveId(DEFAULT_REV_REG_DEF_SEED)) {
            revRegDefHandler.create(DEFAULT_REV_REG_DEF_SEED);
            if (context.lastTransactionException() != null) {
                fail(
                        "Could not create implicit revocation registry definition for rev reg"
                                + " entry: "
                                + context.lastTransactionException().getMessage());
            }
        }
        return context.resolveId(DEFAULT_REV_REG_DEF_SEED);
    }

    private String ensureNonOwnerDid() {
        if (!context.hasEffectiveId(NON_OWNER_DID_SEED)) {
            didHandler.create(NON_OWNER_DID_SEED);
            if (context.lastTransactionException() != null) {
                fail(
                        "Could not create non-owner issuer DID: "
                                + context.lastTransactionException().getMessage());
            }
        }
        return context.resolveId(NON_OWNER_DID_SEED);
    }
}
