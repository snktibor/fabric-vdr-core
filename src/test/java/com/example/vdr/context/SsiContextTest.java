package com.example.vdr.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.hyperledger.fabric.shim.ChaincodeStub;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SsiContext} transaction-scoped wiring behavior.
 *
 * <p>The suite verifies registry/service initialization and deterministic timestamp propagation.
 */
class SsiContextTest {

    /**
     * Verifies context construction initializes all per-transaction dependencies.
     *
     * @throws Exception if stub timestamp wiring fails
     */
    @Test
    void constructorInitializesRegistriesAndServices() throws Exception {
        final ChaincodeStub stub = mock(ChaincodeStub.class);
        final Instant now = Instant.ofEpochMilli(123L);
        when(stub.getTxTimestamp()).thenReturn(now);

        final SsiContext ctx = new SsiContext(stub);

        assertEquals(now, ctx.getTxTimestamp());
        assertNotNull(ctx.getServices());
        assertNotNull(ctx.getDidRegistry());
        assertNotNull(ctx.getSchemaRegistry());
        assertNotNull(ctx.getCredDefRegistry());
        assertNotNull(ctx.getRevRegDefRegistry());
    }
}
