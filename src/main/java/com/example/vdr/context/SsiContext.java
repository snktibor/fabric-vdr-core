package com.example.vdr.context;

import com.example.vdr.ledger.CredentialDefinitionRegistry;
import com.example.vdr.ledger.CredentialRegistry;
import com.example.vdr.ledger.DidRegistry;
import com.example.vdr.ledger.SchemaRegistry;
import java.time.Instant;
import org.hyperledger.fabric.contract.Context;
import org.hyperledger.fabric.shim.ChaincodeStub;

/**
 * Layer 2: Context & Dependency Injection. This class extends the standard Hyperledger Fabric
 * {@link Context} to provide specialized access to SSI-related services and registries.
 *
 * <p>It ensures that all dependencies are instantiated per-transaction, maintaining thread-safety
 * and providing a consistent state for the duration of the request.
 */
public class SsiContext extends Context {

    // Ledger registries for each aggregate type.
    private final DidRegistry didRegistry;
    private final SchemaRegistry schemaRegistry;
    private final CredentialDefinitionRegistry credDefRegistry;
    private final CredentialRegistry credentialRegistry;
    // Service graph built on top of registries.
    private final SsiServices services;

    /**
     * Initializes the SSI context by resolving the client identity and bootstrapping the registry
     * and service graph.
     *
     * @param stub The Fabric ChaincodeStub for ledger interaction.
     */
    public SsiContext(final ChaincodeStub stub) {
        super(stub);
        // All dependencies are created per transaction for thread-safety.
        this.didRegistry = new DidRegistry(stub);
        this.schemaRegistry = new SchemaRegistry(stub);
        this.credDefRegistry = new CredentialDefinitionRegistry(stub);
        this.credentialRegistry = new CredentialRegistry(stub);
        this.services =
                new SsiServices(didRegistry, schemaRegistry, credDefRegistry, credentialRegistry);
    }

    /**
     * Returns the deterministic timestamp of the transaction. This timestamp is set by the Fabric
     * ordering service and is guaranteed to be identical across all peers executing the
     * transaction.
     *
     * @return The {@link Instant} representing the transaction time.
     */
    public Instant getTxTimestamp() {
        return getStub().getTxTimestamp();
    }

    /**
     * Accessor for the DID registry (Layer 4).
     *
     * @return The {@link DidRegistry} instance.
     */
    public DidRegistry getDidRegistry() {
        return didRegistry;
    }

    /**
     * Accessor for the Schema registry (Layer 4).
     *
     * @return The {@link SchemaRegistry} instance.
     */
    public SchemaRegistry getSchemaRegistry() {
        return schemaRegistry;
    }

    /**
     * Accessor for the Credential Definition registry (Layer 4).
     *
     * @return The {@link CredentialDefinitionRegistry} instance.
     */
    public CredentialDefinitionRegistry getCredDefRegistry() {
        return credDefRegistry;
    }

    /**
     * Accessor for the Credential registry (Layer 4).
     *
     * @return The {@link CredentialRegistry} instance.
     */
    public CredentialRegistry getCredentialRegistry() {
        return credentialRegistry;
    }

    /**
     * Exposes the service graph (Layer 3) populated with the necessary registries. This is the
     * primary entry point for the contract layer to invoke business logic.
     *
     * @return The initialized {@link SsiServices} instance.
     */
    public SsiServices getServices() {
        return services;
    }
}
