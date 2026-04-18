package com.example.vdr.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.vdr.exception.VdrException;
import com.example.vdr.ledger.DidRegistry;
import com.example.vdr.ledger.SchemaRegistry;
import com.example.vdr.model.DidDocument;
import com.example.vdr.model.RecordStatus;
import com.example.vdr.model.SchemaRecord;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link SchemaService} schema creation invariants.
 *
 * <p>Validates owner attribution and issuer DID activity checks.
 */
@SuppressWarnings("java:S1192")
@ExtendWith(MockitoExtension.class)
class SchemaServiceTest {

    @Mock private SchemaRegistry schemaRegistry;

    @Mock private DidRegistry didRegistry;

    private SchemaService schemaService;

    /** Initializes service under test with mocked registry dependencies. */
    @BeforeEach
    void setUp() {
        schemaService =
                new SchemaService(
                        schemaRegistry,
                        didRegistry,
                        new AuthorizationService(),
                        new ValidationService());
    }

    /** Verifies schema creation stores caller as owner and parsed attributes. */
    @Test
    void createSchemaSetsOwner() {
        when(schemaRegistry.exists("schema-1")).thenReturn(false);
        final DidDocument issuer = new DidDocument();
        issuer.setId("did:issuer");
        issuer.setStatus(RecordStatus.ACTIVE);
        when(didRegistry.get("did:issuer")).thenReturn(issuer);

        final String attrNamesJson = "[\"name\",\"degree\"]";
        schemaService.createSchema(
                "caller", "schema-1", "did:issuer", "name", "1.0", attrNamesJson);

        final ArgumentCaptor<SchemaRecord> captor = ArgumentCaptor.forClass(SchemaRecord.class);
        verify(schemaRegistry).put(anyString(), captor.capture());
        assertEquals("caller", captor.getValue().getOwnerId());
        assertEquals(List.of("name", "degree"), captor.getValue().getAttrNames());
    }

    /** Verifies schema creation fails when issuer DID is not active. */
    @Test
    void createSchemaRejectsInactiveIssuer() {
        when(schemaRegistry.exists("schema-1")).thenReturn(false);
        final DidDocument issuer = new DidDocument();
        issuer.setId("did:issuer");
        issuer.setStatus(RecordStatus.INACTIVE);
        when(didRegistry.get("did:issuer")).thenReturn(issuer);

        final String attrNamesJson = "[\"name\"]";
        assertThrows(
                VdrException.class,
                () ->
                        schemaService.createSchema(
                                "caller", "schema-1", "did:issuer", "name", "1.0", attrNamesJson));
    }
}
