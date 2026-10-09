package io.github.snktibor.vdr.ledger;

import io.github.snktibor.vdr.model.JwtNonceRecord;
import org.hyperledger.fabric.shim.ChaincodeStub;

/**
 * Layer-4 registry for consumed DID-JWT token ids (one-time-use nonce records).
 *
 * <p>Records are keyed by the deterministic digest of the (issuer DID, {@code jti}) pair computed
 * by {@link io.github.snktibor.vdr.logic.JwtBinding#nonceId}. Replay protection relies on Fabric
 * MVCC: the absent-key read performed before the write is recorded in the transaction read set, so
 * of two concurrent submissions consuming the same token id only the first commits and the second
 * is invalidated at validation time.
 */
public class JwtNonceRegistry extends BaseRegistry<JwtNonceRecord> {

    /**
     * Creates the registry bound to the transaction-scoped stub.
     *
     * @param stub The Fabric ChaincodeStub.
     */
    public JwtNonceRegistry(final ChaincodeStub stub) {
        super(stub, LedgerNamespaces.JWT_NONCE, JwtNonceRecord.class);
    }
}
