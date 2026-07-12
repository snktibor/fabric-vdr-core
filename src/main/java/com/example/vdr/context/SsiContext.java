package com.example.vdr.context;

import com.example.vdr.ledger.ChaincodeEventEmitter;
import com.example.vdr.ledger.CredentialDefinitionRegistry;
import com.example.vdr.ledger.DidRegistry;
import com.example.vdr.ledger.JwtNonceRegistry;
import com.example.vdr.ledger.RevRegDefRegistry;
import com.example.vdr.ledger.RevRegEntryRegistry;
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
    private final RevRegDefRegistry revRegDefRegistry;
    private final RevRegEntryRegistry revRegEntryRegistry;
    private final JwtNonceRegistry jwtNonceRegistry;
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
        this.revRegDefRegistry = new RevRegDefRegistry(stub);
        this.revRegEntryRegistry = new RevRegEntryRegistry(stub);
        this.jwtNonceRegistry = new JwtNonceRegistry(stub);
        this.services =
                new SsiServices(
                        didRegistry,
                        schemaRegistry,
                        credDefRegistry,
                        revRegDefRegistry,
                        revRegEntryRegistry,
                        jwtNonceRegistry,
                        new ChaincodeEventEmitter(stub),
                        stub.getTxTimestamp());
    }

    /**
     * Returns the deterministic timestamp of the transaction. The timestamp comes from the channel
     * header of the transaction proposal, so it is chosen by the submitting client — identical
     * across all endorsing peers, but NOT attested by the ordering service or peer clocks. It is
     * therefore suitable for deterministic time-claim evaluation, while one-time-use token ids (not
     * timestamps) provide the actual replay protection.
     *
     * @return The {@link Instant} representing the client-declared transaction time.
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
     * Accessor for the Revocation Registry Definition registry (Layer 4).
     *
     * @return The {@link RevRegDefRegistry} instance.
     */
    public RevRegDefRegistry getRevRegDefRegistry() {
        return revRegDefRegistry;
    }

    /**
     * Accessor for the Revocation Registry Entry registry (Layer 4).
     *
     * @return The {@link RevRegEntryRegistry} instance.
     */
    public RevRegEntryRegistry getRevRegEntryRegistry() {
        return revRegEntryRegistry;
    }

    /**
     * Accessor for the consumed JWT token id registry (Layer 4).
     *
     * @return The {@link JwtNonceRegistry} instance.
     */
    public JwtNonceRegistry getJwtNonceRegistry() {
        return jwtNonceRegistry;
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
