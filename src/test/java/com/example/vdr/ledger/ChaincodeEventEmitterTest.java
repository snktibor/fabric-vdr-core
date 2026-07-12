package com.example.vdr.ledger;

import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hyperledger.fabric.shim.ChaincodeStub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ChaincodeEventEmitter} deterministic event serialization.
 *
 * <p>Verifies events are forwarded to the stub with canonical (sorted-key) JSON payload bytes, so
 * endorsement responses match across peers.
 */
@ExtendWith(MockitoExtension.class)
class ChaincodeEventEmitterTest {

    @Mock private ChaincodeStub stub;

    /** Verifies emit forwards the event name and canonical UTF-8 payload bytes to the stub. */
    @Test
    void emitSetsEventWithCanonicalJsonPayload() {
        final ChaincodeEventEmitter emitter = new ChaincodeEventEmitter(stub);
        // Insert keys out of order; canonical serialization must sort them.
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("revRegDefId", "revregdef-1");
        payload.put("accumValue", "accum-1");

        emitter.emit(VdrEvents.REV_REG_ENTRY_UPDATED, payload);

        final String expectedJson = "{\"accumValue\":\"accum-1\",\"revRegDefId\":\"revregdef-1\"}";
        verify(stub)
                .setEvent(
                        VdrEvents.REV_REG_ENTRY_UPDATED,
                        expectedJson.getBytes(StandardCharsets.UTF_8));
    }
}
