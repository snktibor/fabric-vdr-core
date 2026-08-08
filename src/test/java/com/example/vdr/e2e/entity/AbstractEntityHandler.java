package com.example.vdr.e2e.entity;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.example.vdr.e2e.support.ScenarioContext;
import com.example.vdr.e2e.support.TransactionExecutor;
import com.github.f4b6a3.uuid.UuidCreator;

/**
 * Convenience base class: any operation an entity does not support throws {@link
 * UnsupportedOperationException}. Concrete handlers override only what applies to them.
 */
public abstract class AbstractEntityHandler implements EntityHandler {

    protected final TransactionExecutor executor;
    protected final ScenarioContext context;

    protected AbstractEntityHandler(
            final TransactionExecutor executor, final ScenarioContext context) {
        this.executor = executor;
        this.context = context;
    }

    /**
     * Returns a fresh UUIDv7 for the object segment of a minted identifier: collision-safe and
     * time-ordered, so ids sort by creation time.
     */
    protected static String uuid7() {
        return UuidCreator.getTimeOrderedEpoch().toString();
    }

    /**
     * Mints an AnonCreds object identifier as a DID URL rooted at the issuer's DID.
     *
     * <p>Feature-file ids are scenario handles only: a real id embeds the issuer DID minted at run
     * time, so it cannot be written down in Gherkin. {@link ScenarioContext#setEffectiveId} maps
     * the handle to the id actually submitted.
     *
     * @param issuerDid registered issuer DID the identifier is rooted at
     * @param objectType object-type path token (see {@link
     *     com.example.vdr.logic.AnonCredsObjectTypes})
     * @return newly minted object identifier
     */
    protected static String mintObjectId(final String issuerDid, final String objectType) {
        return issuerDid + "/anoncreds/v2/" + objectType + "/" + uuid7();
    }

    protected String resolveId(final String rawId) {
        return context.resolveId(rawId);
    }

    /** Helper for {@link #assertExists(String)} implementations. */
    protected void assertReadContains(final String getterTxName, final String rawId) {
        final String resolved = resolveId(rawId);
        try {
            final String response = executor.evaluate(getterTxName, resolved);
            assertTrue(
                    response.contains(resolved),
                    () ->
                            String.format(
                                    "Ledger response for %s '%s' did not contain its id: %s",
                                    typeKey(), resolved, response));
        } catch (Exception e) {
            fail("Ledger query failed for " + typeKey() + " '" + resolved + "': " + e.getMessage());
        }
    }

    @Override
    public void create(final String rawId) {
        throw new UnsupportedOperationException(typeKey() + " does not support create");
    }

    @Override
    public void existingOnLedger(final String rawId) {
        throw new UnsupportedOperationException(typeKey() + " does not support setup");
    }

    @Override
    public void query(final String rawId) {
        final String resolved = resolveId(rawId);
        context.setLastQueryResponse(null);
        context.setLastTransactionException(null);
        try {
            context.setLastQueryResponse(executor.evaluate(getterTxName(), resolved));
        } catch (Exception e) {
            context.setLastTransactionException(e);
        }
    }

    @Override
    public void update(final String rawId) {
        throw new UnsupportedOperationException(typeKey() + " does not support update");
    }

    @Override
    public void deactivate(final String rawId) {
        throw new UnsupportedOperationException(typeKey() + " does not support deactivate");
    }

    @Override
    public void deactivateWithInvalidJwt(final String rawId) {
        throw new UnsupportedOperationException(
                typeKey() + " does not support invalid-JWT deactivation");
    }

    @Override
    public void assertExists(final String rawId) {
        assertReadContains(getterTxName(), rawId);
    }

    @Override
    public void assertStatus(final String rawId, final String expectedStatus) {
        throw new UnsupportedOperationException(typeKey() + " has no status field");
    }

    /** The evaluate-transaction name used to fetch this entity by id. */
    protected abstract String getterTxName();

    /** Override to enable list support. Default: unsupported. */
    protected String listTxName() {
        throw new UnsupportedOperationException(typeKey() + " does not support list");
    }

    @Override
    public void list() {
        context.setLastQueryResponse(null);
        context.setLastTransactionException(null);
        try {
            context.setLastQueryResponse(executor.evaluate(listTxName()));
        } catch (Exception e) {
            context.setLastTransactionException(e);
        }
    }
}
