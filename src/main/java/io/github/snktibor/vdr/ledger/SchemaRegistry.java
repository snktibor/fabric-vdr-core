package io.github.snktibor.vdr.ledger;

import io.github.snktibor.vdr.model.SchemaRecord;
import org.hyperledger.fabric.shim.ChaincodeStub;

/**
 * Ledger data-access registry for {@link SchemaRecord} entities.
 *
 * <p>This layer-4 registry encapsulates Fabric world-state access for schema records and binds all
 * operations to the dedicated schema namespace to keep key organization deterministic and
 * collision-free.
 */
public class SchemaRegistry extends BaseRegistry<SchemaRecord> {

    /**
     * Creates a transaction-scoped registry for schema persistence.
     *
     * @param stub Fabric chaincode stub used for world-state interaction
     */
    public SchemaRegistry(final ChaincodeStub stub) {
        super(stub, LedgerNamespaces.SCHEMA, SchemaRecord.class);
    }
}
