package org.hyperledger.fabric.contract;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.hyperledger.fabric.shim.ChaincodeStub;

/**
 * Lightweight test double for Fabric {@code ClientIdentity}.
 *
 * <p>Provides deterministic identity extraction and controllable constructor failure behavior for
 * context-layer unit tests.
 */
public class ClientIdentity {

    private static final AtomicInteger CONSTRUCTION_COUNT = new AtomicInteger(0);
    private static final AtomicBoolean FAIL_ON_SECOND = new AtomicBoolean(false);

    private final String id;

    /**
     * Creates identity instance from stub creator bytes.
     *
     * @param stub chaincode stub providing creator payload
     * @throws Exception when configured test failure condition is triggered
     */
    public ClientIdentity(final ChaincodeStub stub) throws Exception {
        final int count = CONSTRUCTION_COUNT.incrementAndGet();
        if (FAIL_ON_SECOND.get() && count == 2) {
            throw new RuntimeException("boom");
        }
        final byte[] creator = stub.getCreator();
        if (creator == null) {
            this.id = null;
        } else {
            this.id = new String(creator, StandardCharsets.UTF_8);
        }
    }

    /** Resets static test state between test executions. */
    public static void resetTestState() {
        CONSTRUCTION_COUNT.set(0);
        FAIL_ON_SECOND.set(false);
    }

    /** Configures constructor to fail on the second instantiation. */
    public static void failOnSecondConstruction() {
        FAIL_ON_SECOND.set(true);
    }

    /**
     * Returns parsed identity value extracted from stub creator bytes.
     *
     * @return caller identity string or {@code null}
     */
    public String getId() {
        return id;
    }
}
