package io.github.snktibor.vdr.ledger;

import io.github.snktibor.vdr.model.DidDocument;
import org.hyperledger.fabric.shim.ChaincodeStub;

/**
 * Ledger data-access registry for {@link DidDocument} entries.
 *
 * <p>This layer-4 registry isolates DID world-state operations behind a dedicated composite-key
 * namespace, allowing business services to perform DID logic without direct dependency on Fabric
 * SDK APIs.
 */
public class DidRegistry extends BaseRegistry<DidDocument> {

    /**
     * Creates a transaction-scoped registry for DID document persistence.
     *
     * @param stub Fabric chaincode stub associated with the current invocation
     */
    public DidRegistry(final ChaincodeStub stub) {
        super(stub, LedgerNamespaces.DID, DidDocument.class);
    }
}
