package com.example.vdr.logic;

/**
 * Authenticated JWT principal resolved by {@link JwtValidator}.
 *
 * <p>Represents the verified signer identity and key reference extracted from a DID-JWT token.
 */
public final class JwtPrincipal {

    private final String signerDid;
    private final String keyId;

    /**
     * Creates an immutable principal snapshot for a verified token.
     *
     * @param signerDid DID from verified issuer claim
     * @param keyId DID URL key id used to verify signature
     */
    public JwtPrincipal(final String signerDid, final String keyId) {
        this.signerDid = signerDid;
        this.keyId = keyId;
    }

    /**
     * Returns the verified signer DID.
     *
     * @return signer DID
     */
    public String getSignerDid() {
        return signerDid;
    }

    /**
     * Returns the verification method id used for signature verification.
     *
     * @return DID URL key id
     */
    public String getKeyId() {
        return keyId;
    }
}
