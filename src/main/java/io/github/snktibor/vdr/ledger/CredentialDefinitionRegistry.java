package io.github.snktibor.vdr.ledger;

import io.github.snktibor.vdr.model.CredentialDefinitionRecord;
import org.hyperledger.fabric.shim.ChaincodeStub;

/**
 * Ledger data-access registry for {@link CredentialDefinitionRecord} entities.
 *
 * <p>This is a layer-4 component that delegates low-level state operations to {@link BaseRegistry}
 * while binding the credential definition namespace to a dedicated composite-key prefix. The
 * explicit namespace prevents key collisions with other SSI artifact types.
 */
public class CredentialDefinitionRegistry extends BaseRegistry<CredentialDefinitionRecord> {

    /**
     * Creates a transaction-scoped registry for credential definition persistence.
     *
     * @param stub Fabric chaincode stub used to access world state in this transaction
     */
    public CredentialDefinitionRegistry(final ChaincodeStub stub) {
        super(stub, LedgerNamespaces.CRED_DEF, CredentialDefinitionRecord.class);
    }
}
