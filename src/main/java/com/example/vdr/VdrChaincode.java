package com.example.vdr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.hyperledger.fabric.contract.ContractRouter;

/**
 * Chaincode bootstrap entrypoint that prepares runtime arguments for Fabric.
 *
 * <p>The class normalizes peer address configuration from CLI/environment inputs, applies required
 * gRPC runtime property adjustments, and then delegates contract startup to {@link ContractRouter}.
 */
public final class VdrChaincode {
    /** Supplier indirection used to read peer address environment value. */
    static Supplier<String> corePeerAddressSupplier = () -> System.getenv("CORE_PEER_ADDRESS");

    /** Utility class constructor is private to prevent instantiation. */
    private VdrChaincode() {}

    /**
     * Starts chaincode process by normalizing peer address arguments and delegating execution to
     * Fabric's contract router.
     *
     * @param args process arguments passed by chaincode launcher
     * @throws Exception if contract router initialization or startup fails
     */
    public static void main(final String[] args) throws Exception {
        // Some deployment environments require explicit authority check disablement.
        System.setProperty("io.grpc.ManagedChannelBuilder.disable_check_authority", "true");

        final List<String> normalizedArgs = new ArrayList<>(Arrays.asList(args));
        boolean peerAddressNormalized = false;

        // Normalize explicit --peerAddress argument to host:port format.
        for (int i = 0; i < normalizedArgs.size() - 1; i++) {
            if ("--peerAddress".equals(normalizedArgs.get(i))) {
                final String address = normalizedArgs.get(i + 1);
                normalizedArgs.set(i + 1, sanitizePeerAddress(address));
                peerAddressNormalized = true;
                break;
            }
        }

        // Fallback to CORE_PEER_ADDRESS when CLI argument is not provided.
        if (!peerAddressNormalized) {
            final String corePeerAddress = corePeerAddressSupplier.get();
            if (corePeerAddress != null && !corePeerAddress.isBlank()) {
                normalizedArgs.add("--peerAddress");
                normalizedArgs.add(sanitizePeerAddress(corePeerAddress));
            }
        }
        // ContractRouter picks up @Contract classes from the classpath.
        ContractRouter.main(normalizedArgs.toArray(new String[0]));
    }

    /**
     * Normalizes peer address values to Fabric Java shim expected format.
     *
     * <p>The method strips protocol prefixes and leading slashes, then appends default peer port
     * {@code 7051} when port is absent.
     *
     * @param address raw peer address from CLI or environment
     * @return sanitized peer address in {@code host:port} format when possible
     */
    private static String sanitizePeerAddress(final String address) {
        if (address == null || address.isBlank()) {
            return address;
        }

        String sanitized = address.trim();
        final int schemeIndex = sanitized.indexOf("://");
        if (schemeIndex > -1) {
            sanitized = sanitized.substring(schemeIndex + 3);
        }

        while (sanitized.startsWith("/")) {
            sanitized = sanitized.substring(1);
        }

        if (!sanitized.contains(":")) {
            sanitized = sanitized + ":7051";
        }

        return sanitized;
    }
}
