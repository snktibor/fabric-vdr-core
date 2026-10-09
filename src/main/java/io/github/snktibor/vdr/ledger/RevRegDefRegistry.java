package io.github.snktibor.vdr.ledger;

import io.github.snktibor.vdr.model.RevRegDefRecord;
import org.hyperledger.fabric.shim.ChaincodeStub;

/**
 * Ledger data-access registry for {@link RevRegDefRecord} entities.
 *
 * <p>This is a layer-4 component that delegates low-level state operations to {@link BaseRegistry}
 * while binding the revocation registry definition namespace to a dedicated composite-key prefix.
 * The explicit namespace prevents key collisions with other SSI artifact types.
 */
public class RevRegDefRegistry extends BaseRegistry<RevRegDefRecord> {

    /**
     * Creates a transaction-scoped registry for revocation registry definition persistence.
     *
     * @param stub Fabric chaincode stub used to access world state in this transaction
     */
    public RevRegDefRegistry(final ChaincodeStub stub) {
        super(stub, LedgerNamespaces.REV_REG_DEF, RevRegDefRecord.class);
    }
}
