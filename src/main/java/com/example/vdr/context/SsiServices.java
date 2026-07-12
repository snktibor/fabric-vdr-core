package com.example.vdr.context;

import com.example.vdr.ledger.CredentialDefinitionRegistry;
import com.example.vdr.ledger.DidRegistry;
import com.example.vdr.ledger.JwtNonceRegistry;
import com.example.vdr.ledger.RevRegDefRegistry;
import com.example.vdr.ledger.RevRegEntryRegistry;
import com.example.vdr.ledger.SchemaRegistry;
import com.example.vdr.logic.AuthorizationService;
import com.example.vdr.logic.CredentialDefinitionService;
import com.example.vdr.logic.DidService;
import com.example.vdr.logic.JwtValidator;
import com.example.vdr.logic.RevRegDefService;
import com.example.vdr.logic.RevRegEntryService;
import com.example.vdr.logic.SchemaService;
import com.example.vdr.logic.ValidationService;

/**
 * Per-transaction dependency container that wires SSI business services.
 *
 * <p>Within the 4-tier architecture this class belongs to the context/DI layer. It creates a fresh
 * service graph for each chaincode invocation, ensuring transaction isolation and thread-safe usage
 * of service dependencies.
 */
public class SsiServices {

    private final AuthorizationService authorizationService;
    private final ValidationService validationService;
    private final JwtValidator jwtValidator;
    private final DidService didService;
    private final SchemaService schemaService;
    private final CredentialDefinitionService credentialDefinitionService;
    private final RevRegDefService revRegDefService;
    private final RevRegEntryService revRegEntryService;

    /**
     * Builds the service graph for one transaction using transaction-scoped registries.
     *
     * <p>Cross-cutting concerns (authorization and validation) are instantiated once and shared by
     * domain services so ownership and fail-fast checks remain consistent across DID, schema,
     * credential definition, and revocation registry flows.
     *
     * @param didRegistry DID data access component for ledger reads/writes
     * @param schemaRegistry schema data access component for ledger reads/writes
     * @param credDefRegistry credential definition data access component
     * @param revRegDefRegistry revocation registry definition data access component
     * @param revRegEntryRegistry revocation registry entry data access component
     * @param jwtNonceRegistry consumed JWT token id data access component
     */
    public SsiServices(
            final DidRegistry didRegistry,
            final SchemaRegistry schemaRegistry,
            final CredentialDefinitionRegistry credDefRegistry,
            final RevRegDefRegistry revRegDefRegistry,
            final RevRegEntryRegistry revRegEntryRegistry,
            final JwtNonceRegistry jwtNonceRegistry) {
        // Cross-cutting services first, then domain services that depend on them.
        this.authorizationService = new AuthorizationService();
        this.validationService = new ValidationService();
        this.jwtValidator = new JwtValidator(didRegistry, jwtNonceRegistry);
        this.didService = new DidService(didRegistry, authorizationService, validationService);
        this.schemaService = new SchemaService(schemaRegistry, didRegistry, validationService);
        this.credentialDefinitionService =
                new CredentialDefinitionService(
                        credDefRegistry, schemaRegistry, didRegistry, validationService);
        this.revRegDefService =
                new RevRegDefService(
                        revRegDefRegistry, credDefRegistry, didRegistry, validationService);
        this.revRegEntryService =
                new RevRegEntryService(revRegEntryRegistry, revRegDefRegistry, validationService);
    }

    /**
     * Exposes DID business operations to the contract layer.
     *
     * @return transaction-scoped {@link DidService} with injected dependencies
     */
    public DidService getDidService() {
        return didService;
    }

    /**
     * Exposes the shared transaction JWT validator.
     *
     * @return transaction-scoped {@link JwtValidator}
     */
    public JwtValidator getJwtValidator() {
        return jwtValidator;
    }

    /**
     * Exposes schema business operations to the contract layer.
     *
     * @return transaction-scoped {@link SchemaService} with injected dependencies
     */
    public SchemaService getSchemaService() {
        return schemaService;
    }

    /**
     * Exposes credential definition business operations to the contract layer.
     *
     * @return transaction-scoped {@link CredentialDefinitionService}
     */
    public CredentialDefinitionService getCredentialDefinitionService() {
        return credentialDefinitionService;
    }

    /**
     * Exposes revocation registry definition business operations to the contract layer.
     *
     * @return transaction-scoped {@link RevRegDefService}
     */
    public RevRegDefService getRevRegDefService() {
        return revRegDefService;
    }

    /**
     * Exposes revocation registry entry business operations to the contract layer.
     *
     * @return transaction-scoped {@link RevRegEntryService}
     */
    public RevRegEntryService getRevRegEntryService() {
        return revRegEntryService;
    }
}
