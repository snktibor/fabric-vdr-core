package com.example.vdr.ledger;

import com.example.vdr.util.JsonUtil;
import java.nio.charset.StandardCharsets;
import org.hyperledger.fabric.shim.ChaincodeStub;

/**
 * Layer-4 component emitting chaincode events through the transaction stub.
 *
 * <p>Payloads are serialized with the project's canonical JSON mapper (sorted keys), so the event
 * bytes are identical on every endorsing peer — a requirement for the endorsement responses to
 * match. Events ride the transaction: they are delivered to subscribers only when the transaction
 * commits, and never for failed or invalidated transactions.
 *
 * <p>Fabric supports a single event per transaction ({@code setEvent} overwrites earlier calls).
 * Every VDR transaction performs exactly one state change and therefore emits exactly once; a
 * future transaction making multiple changes must aggregate them into one event.
 */
public class ChaincodeEventEmitter {

    private final ChaincodeStub stub;

    /**
     * Creates an emitter bound to the transaction-scoped stub.
     *
     * @param stub The Fabric ChaincodeStub.
     */
    public ChaincodeEventEmitter(final ChaincodeStub stub) {
        this.stub = stub;
    }

    /**
     * Emits one chaincode event with a canonical-JSON payload.
     *
     * @param eventName event name from {@link VdrEvents}
     * @param payload payload object serialized deterministically to JSON
     */
    public void emit(final String eventName, final Object payload) {
        stub.setEvent(
                eventName, JsonUtil.toCanonicalJson(payload).getBytes(StandardCharsets.UTF_8));
    }
}
