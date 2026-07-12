package com.example.vdr.e2e.support;

import java.security.KeyPair;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-scenario, mutable state shared between step classes via PicoContainer injection. Keeps the
 * Cucumber-style "last X" semantics (last transaction error, last query response, last effective id
 * for each entity type) without sprinkling fields across every step class.
 */
public class ScenarioContext {

    private final Map<String, KeyPair> didKeys = new HashMap<>();
    private final Map<String, String> effectiveIdsByRawId = new HashMap<>();
    private final Map<String, String> ownerDidByEffectiveId = new HashMap<>();

    private Exception lastTransactionException;
    private String lastQueryResponse;
    private String heldSigningDid;
    private String lastChaincodeEventName;
    private String lastChaincodeEventPayload;

    public Map<String, KeyPair> didKeys() {
        return didKeys;
    }

    public KeyPair requireDidKeyPair(final String did) {
        final KeyPair keyPair = didKeys.get(did);
        if (keyPair == null) {
            throw new IllegalStateException("No ES256 keypair found for DID: " + did);
        }
        return keyPair;
    }

    /** Records the effective (UUIDv7-based) id assigned to an entity from the feature file. */
    public void setEffectiveId(final String rawId, final String effectiveId) {
        if (rawId == null || rawId.isBlank()) {
            throw new IllegalArgumentException(
                    "Test framework error: rawId cannot be null or empty.");
        }
        effectiveIdsByRawId.put(rawId, effectiveId);
    }

    /** Returns the recorded effective id for {@code rawId} */
    public String resolveId(final String rawId) {
        if (rawId == null || rawId.isBlank()) {
            throw new IllegalArgumentException(
                    "Test framework error: Step definition must explicitly provide a rawId.");
        }
        return effectiveIdsByRawId.getOrDefault(rawId, rawId);
    }

    public boolean hasEffectiveId(final String rawId) {
        return rawId != null && effectiveIdsByRawId.containsKey(rawId);
    }

    /**
     * Records which DID owns an entity (schema, cred def, ...) so future protected operations on it
     * can sign with the right keypair.
     */
    public void recordOwner(final String effectiveId, final String ownerDid) {
        if (effectiveId == null || ownerDid == null) {
            throw new IllegalArgumentException(
                    "Test framework error: effectiveId and ownerDid must be non-null.");
        }
        ownerDidByEffectiveId.put(effectiveId, ownerDid);
    }

    public String requireOwner(final String effectiveId) {
        final String ownerDid = ownerDidByEffectiveId.get(effectiveId);
        if (ownerDid == null) {
            throw new IllegalStateException("No owner DID recorded for entity id: " + effectiveId);
        }
        return ownerDid;
    }

    public Exception lastTransactionException() {
        return lastTransactionException;
    }

    public void setLastTransactionException(final Exception e) {
        this.lastTransactionException = e;
    }

    public String lastQueryResponse() {
        return lastQueryResponse;
    }

    public void setLastQueryResponse(final String response) {
        this.lastQueryResponse = response;
    }

    /**
     * Returns the DID whose signing key the scenario explicitly holds, or {@code null}.
     *
     * <p>Tokens are scope-bound to one invocation, so a scenario can no longer hold a pre-minted
     * JWT; it holds the signing identity and mints the token at submission time.
     */
    public String heldSigningDid() {
        return heldSigningDid;
    }

    /** Records the DID whose registered signing key later protected steps should sign with. */
    public void holdSigningDid(final String did) {
        this.heldSigningDid = did;
    }

    /** Records (or clears, with nulls) the last matching chaincode event received. */
    public void recordChaincodeEvent(final String name, final String payload) {
        this.lastChaincodeEventName = name;
        this.lastChaincodeEventPayload = payload;
    }

    public String lastChaincodeEventName() {
        return lastChaincodeEventName;
    }

    public String lastChaincodeEventPayload() {
        return lastChaincodeEventPayload;
    }
}
