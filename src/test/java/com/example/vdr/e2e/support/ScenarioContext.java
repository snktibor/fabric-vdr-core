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

    private Exception lastTransactionException;
    private String lastQueryResponse;
    private String heldJwt;
    private String heldJwtSubjectDid;

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

    /** Records the effective (UUID-based) id assigned to an entity from the feature file. */
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

    public String heldJwt() {
        return heldJwt;
    }

    public String heldJwtSubjectDid() {
        return heldJwtSubjectDid;
    }

    public void holdJwt(final String subjectDid, final String jwt) {
        this.heldJwtSubjectDid = subjectDid;
        this.heldJwt = jwt;
    }
}
