package com.example.vdr.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Model-layer DTO recording one consumed DID-JWT token id ({@code jti}) on the ledger.
 *
 * <p>Every accepted state-changing token writes exactly one nonce record, making tokens one-time
 * use: a second transaction presenting the same (issuer DID, {@code jti}) pair is rejected as a
 * replay. The record keeps the token's expiry so that a future housekeeping transaction can prune
 * entries whose replay window has long closed; no pruning is implemented yet.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class JwtNonceRecord {

    // Verified issuer DID of the consumed token.
    private String issuerDid;
    // Token id claim (jti) of the consumed token, unique per issuer.
    private String jti;
    // Token expiry as epoch seconds; entries past expiry (plus skew) are prunable.
    private long expiresAtEpochSeconds;

    /** Creates an empty record instance for JSON deserialization frameworks. */
    public JwtNonceRecord() {}

    /**
     * Creates a consumed-token record.
     *
     * @param issuerDid verified issuer DID of the token
     * @param jti token id claim value
     * @param expiresAtEpochSeconds token expiry as epoch seconds
     */
    public JwtNonceRecord(
            final String issuerDid, final String jti, final long expiresAtEpochSeconds) {
        this.issuerDid = issuerDid;
        this.jti = jti;
        this.expiresAtEpochSeconds = expiresAtEpochSeconds;
    }

    /**
     * Returns the verified issuer DID of the consumed token.
     *
     * @return issuer DID string
     */
    public String getIssuerDid() {
        return issuerDid;
    }

    /**
     * Sets the issuer DID, typically during JSON binding.
     *
     * @param issuerDid issuer DID string
     */
    public void setIssuerDid(final String issuerDid) {
        this.issuerDid = issuerDid;
    }

    /**
     * Returns the consumed token id claim value.
     *
     * @return jti claim value
     */
    public String getJti() {
        return jti;
    }

    /**
     * Sets the token id claim value, typically during JSON binding.
     *
     * @param jti jti claim value
     */
    public void setJti(final String jti) {
        this.jti = jti;
    }

    /**
     * Returns the token expiry used to decide when this record becomes prunable.
     *
     * @return expiry as epoch seconds
     */
    public long getExpiresAtEpochSeconds() {
        return expiresAtEpochSeconds;
    }

    /**
     * Sets the token expiry, typically during JSON binding.
     *
     * @param expiresAtEpochSeconds expiry as epoch seconds
     */
    public void setExpiresAtEpochSeconds(final long expiresAtEpochSeconds) {
        this.expiresAtEpochSeconds = expiresAtEpochSeconds;
    }
}
