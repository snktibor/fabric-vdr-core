package io.github.snktibor.vdr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import java.util.List;
import org.hyperledger.fabric.contract.ContractRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VdrChaincode} bootstrap behavior.
 *
 * <p>These tests verify peer-address normalization, environment fallback, gRPC property setup, and
 * internal sanitization logic used at startup.
 */
class VdrChaincodeTest {

    /** Resets mutable shared test state before each test case. */
    @BeforeEach
    void resetRouterArgs() {
        ContractRouter.LAST_ARGS.set(null);
        VdrChaincode.corePeerAddressSupplier = () -> null;
    }

    /**
     * Verifies CLI peer address values are normalized before router delegation.
     *
     * @throws Exception if chaincode startup invocation fails
     */
    @Test
    void mainNormalizesPeerAddressFromArgs() throws Exception {
        VdrChaincode.main(new String[] {"--peerAddress", "grpc://peer0.org1"});

        assertEquals(List.of("--peerAddress", "peer0.org1"), ContractRouter.LAST_ARGS.get());
        assertEquals(
                "true",
                System.getProperty("io.grpc.ManagedChannelBuilder.disable_check_authority"));
    }

    /**
     * Verifies CORE_PEER_ADDRESS fallback is appended when CLI argument is absent.
     *
     * @throws Exception if chaincode startup invocation fails
     */
    @Test
    void mainAddsPeerAddressFromEnvWhenMissing() throws Exception {
        VdrChaincode.corePeerAddressSupplier = () -> "grpcs://peer1.org1";
        VdrChaincode.main(new String[0]);

        assertEquals(List.of("--peerAddress", "peer1.org1"), ContractRouter.LAST_ARGS.get());
    }

    /**
     * Verifies peer address sanitization preserves null and blank inputs.
     *
     * @throws Exception if reflective access to sanitize method fails
     */
    @Test
    void sanitizePeerAddressHandlesBlankAndNull() throws Exception {
        final Method method =
                VdrChaincode.class.getDeclaredMethod("sanitizePeerAddress", String.class);
        method.setAccessible(true);

        assertNull(method.invoke(null, new Object[] {null}));
        assertEquals("   ", method.invoke(null, "   "));
    }

    /**
     * Verifies protocol and slash cleanup while retaining explicit ports.
     *
     * @throws Exception if reflective access to sanitize method fails
     */
    @Test
    void sanitizePeerAddressRemovesSchemeAndSlashes() throws Exception {
        final Method method =
                VdrChaincode.class.getDeclaredMethod("sanitizePeerAddress", String.class);
        method.setAccessible(true);

        assertEquals("peer0", method.invoke(null, "grpc:///peer0"));
        assertEquals("peer1:9051", method.invoke(null, "grpcs://peer1:9051"));
    }
}
