package com.example.vdr.ledger;

import com.example.vdr.model.RevRegEntryRecord;
import org.hyperledger.fabric.shim.ChaincodeStub;

/**
 * Ledger data-access registry for {@link RevRegEntryRecord} entities.
 *
 * <p>This is a layer-4 component that delegates low-level state operations to {@link BaseRegistry}
 * while binding the revocation registry entry namespace to a dedicated composite-key prefix. The
 * explicit namespace prevents key collisions with other SSI artifact types.
 *
 * <p>Records are keyed by the parent revocation registry definition id rather than by the entry's
 * own id: an entry exists one-to-one with its definition, so keying by {@code revRegDefId} enforces
 * that invariant at the storage layer and makes {@code exists}/{@code get}/{@code put} the natural
 * lookups for uniqueness checks, retrieval, and in-place accumulator updates. The entry's own
 * identifier is preserved inside the stored payload.
 */
public class RevRegEntryRegistry extends BaseRegistry<RevRegEntryRecord> {

    /**
     * Creates a transaction-scoped registry for revocation registry entry persistence.
     *
     * @param stub Fabric chaincode stub used to access world state in this transaction
     */
    public RevRegEntryRegistry(final ChaincodeStub stub) {
        super(stub, LedgerNamespaces.REV_REG_ENTRY, RevRegEntryRecord.class);
    }
}
