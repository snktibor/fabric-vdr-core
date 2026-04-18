package com.example.vdr.context;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import com.example.vdr.ledger.CredentialDefinitionRegistry;
import com.example.vdr.ledger.CredentialRegistry;
import com.example.vdr.ledger.DidRegistry;
import com.example.vdr.ledger.SchemaRegistry;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SsiServices} dependency graph construction.
 *
 * <p>Ensures all domain services are created when transaction-scoped registries are provided.
 */
class SsiServicesTest {

    /** Verifies constructor wiring exposes all expected service instances. */
    @Test
    void constructorWiresServices() {
        final DidRegistry didRegistry = mock(DidRegistry.class);
        final SchemaRegistry schemaRegistry = mock(SchemaRegistry.class);
        final CredentialDefinitionRegistry credDefRegistry =
                mock(CredentialDefinitionRegistry.class);
        final CredentialRegistry credentialRegistry = mock(CredentialRegistry.class);

        final SsiServices services =
                new SsiServices(didRegistry, schemaRegistry, credDefRegistry, credentialRegistry);

        assertNotNull(services.getDidService());
        assertNotNull(services.getSchemaService());
        assertNotNull(services.getCredentialDefinitionService());
        assertNotNull(services.getCredentialService());
    }
}
