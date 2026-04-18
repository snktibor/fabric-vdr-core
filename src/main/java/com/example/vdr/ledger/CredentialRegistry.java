package com.example.vdr.ledger;

import com.example.vdr.model.CredentialRecord;
import org.hyperledger.fabric.shim.ChaincodeStub;

/**
 * Ledger data-access registry for {@link CredentialRecord} entities.
 *
 * <p>As part of the layer-4 data-access boundary, this class constrains all credential persistence
 * to the {@code CRED} composite-key namespace while reusing generic CRUD/list functionality from
 * {@link BaseRegistry}.
 */
public class CredentialRegistry extends BaseRegistry<CredentialRecord> {

    /**
     * Creates a transaction-scoped registry for credential persistence.
     *
     * @param stub Fabric chaincode stub used to read and write world state
     */
    public CredentialRegistry(final ChaincodeStub stub) {
        super(stub, LedgerNamespaces.CREDENTIAL, CredentialRecord.class);
    }
}
