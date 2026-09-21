package com.example.vdr.e2e.support;

import java.security.KeyPair;
import java.util.Arrays;

/**
 * One JWT-protected transaction a concurrency scenario wants to race against the others.
 *
 * <p>The token is not minted here: {@link TransactionExecutor#submitTogether} mints it at
 * endorsement time, so the one-time {@code jti} and the transaction digest belong to the invocation
 * that is actually endorsed.
 */
public final class ConcurrentSubmission {

    private final String label;
    private final String txName;
    private final String signerDid;
    private final KeyPair keyPair;
    private final String[] payloadArgs;

    private ConcurrentSubmission(
            final String label,
            final String txName,
            final String signerDid,
            final KeyPair keyPair,
            final String[] payloadArgs) {
        this.label = label;
        this.txName = txName;
        this.signerDid = signerDid;
        this.keyPair = keyPair;
        this.payloadArgs = payloadArgs;
    }

    /**
     * Describes a signed submission.
     *
     * @param label short scenario-facing name, quoted back in assertion messages
     * @param txName chaincode transaction name
     * @param signerDid DID whose registered key signs the token
     * @param keyPair signing key pair of {@code signerDid}
     * @param payloadArgs transaction arguments without the trailing token
     */
    public static ConcurrentSubmission signed(
            final String label,
            final String txName,
            final String signerDid,
            final KeyPair keyPair,
            final String... payloadArgs) {
        return new ConcurrentSubmission(
                label, txName, signerDid, keyPair, Arrays.copyOf(payloadArgs, payloadArgs.length));
    }

    public String label() {
        return label;
    }

    public String txName() {
        return txName;
    }

    public String signerDid() {
        return signerDid;
    }

    public KeyPair keyPair() {
        return keyPair;
    }

    public String[] payloadArgs() {
        return Arrays.copyOf(payloadArgs, payloadArgs.length);
    }
}
