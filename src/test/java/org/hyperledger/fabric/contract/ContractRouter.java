package org.hyperledger.fabric.contract;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal test double for Fabric {@code ContractRouter}.
 *
 * <p>Captures the last argument vector so chaincode bootstrap tests can verify normalized startup
 * parameters without invoking real router logic.
 */
public final class ContractRouter {
    /** Captured arguments from the most recent {@link #main(String[])} call. */
    public static final AtomicReference<List<String>> LAST_ARGS = new AtomicReference<>();

    /** Utility class constructor is private to prevent instantiation. */
    private ContractRouter() {}

    /**
     * Captures incoming arguments for test assertions.
     *
     * @param args startup arguments supplied by chaincode bootstrap
     */
    public static void main(final String[] args) {
        LAST_ARGS.set(Arrays.asList(args));
    }
}
