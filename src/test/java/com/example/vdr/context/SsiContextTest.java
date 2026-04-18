package com.example.vdr.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.vdr.exception.VdrException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.hyperledger.fabric.contract.ClientIdentity;
import org.hyperledger.fabric.shim.ChaincodeStub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SsiContext} transaction-scoped wiring behavior.
 *
 * <p>The suite verifies registry/service initialization, caller extraction, timestamp propagation,
 * and deterministic exception mapping.
 */
class SsiContextTest {

    /** Resets static client identity test hooks before each execution. */
    @BeforeEach
    void resetClientIdentityStub() {
        ClientIdentity.resetTestState();
    }

    /**
     * Verifies context construction initializes all per-transaction dependencies.
     *
     * @throws Exception if stub timestamp wiring fails
     */
    @Test
    void constructorInitializesRegistriesAndServices() throws Exception {
        final ChaincodeStub stub = mock(ChaincodeStub.class);
        when(stub.getCreator()).thenReturn("caller".getBytes(StandardCharsets.UTF_8));
        final Instant now = Instant.ofEpochMilli(123L);
        when(stub.getTxTimestamp()).thenReturn(now);

        final SsiContext ctx = new SsiContext(stub);

        assertEquals(now, ctx.getTxTimestamp());
        assertEquals("caller", ctx.getCallerId());
        assertNotNull(ctx.getServices());
        assertNotNull(ctx.getDidRegistry());
        assertNotNull(ctx.getSchemaRegistry());
        assertNotNull(ctx.getCredDefRegistry());
        assertNotNull(ctx.getCredentialRegistry());
    }

    /** Verifies client identity construction failures are mapped to domain errors. */
    @Test
    void constructorWrapsClientIdentityErrors() {
        final ChaincodeStub stub = mock(ChaincodeStub.class);
        when(stub.getCreator()).thenReturn("caller".getBytes(StandardCharsets.UTF_8));
        ClientIdentity.failOnSecondConstruction();

        final VdrException exception = assertThrows(VdrException.class, () -> new SsiContext(stub));

        assertEquals("INVALID_ARGUMENT", exception.getErrorCode());
    }
}
