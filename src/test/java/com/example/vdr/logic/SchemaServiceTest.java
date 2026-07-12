package com.example.vdr.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.vdr.exception.VdrException;
import com.example.vdr.ledger.ChaincodeEventEmitter;
import com.example.vdr.ledger.DidRegistry;
import com.example.vdr.ledger.SchemaRegistry;
import com.example.vdr.ledger.VdrEvents;
import com.example.vdr.model.DidDocument;
import com.example.vdr.model.RecordStatus;
import com.example.vdr.model.SchemaRecord;
import java.time.Instant;
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

    private static final String SCHEMA_ID = "schema:vdr:018f6c02-3a5e-7cbb-9d3e-5f2a4b6c8d0e";

    private static final Instant TX_TIME = Instant.parse("2026-07-12T10:15:30Z");

    @Mock private SchemaRegistry schemaRegistry;

    @Mock private DidRegistry didRegistry;

    @Mock private ChaincodeEventEmitter eventEmitter;

    private SchemaService schemaService;

    /** Initializes service under test with mocked registry dependencies. */
    @BeforeEach
    void setUp() {
        schemaService =
                new SchemaService(
                        schemaRegistry,
                        didRegistry,
                        new ValidationService(),
                        eventEmitter,
                        TX_TIME);
    }

    /** Verifies schema creation stores signer DID as owner and parsed attributes. */
    @Test
    void createSchemaSetsOwner() {
        when(schemaRegistry.exists(SCHEMA_ID)).thenReturn(false);
        final DidDocument issuer = new DidDocument();
        issuer.setId("did:issuer");
        issuer.setStatus(RecordStatus.ACTIVE);
        when(didRegistry.get("did:issuer")).thenReturn(issuer);

        final String attrNamesJson = "[\"name\",\"degree\"]";
        schemaService.createSchema(
                "did:issuer", SCHEMA_ID, "did:issuer", "name", "1.0", attrNamesJson);

        final ArgumentCaptor<SchemaRecord> captor = ArgumentCaptor.forClass(SchemaRecord.class);
        verify(schemaRegistry).put(anyString(), captor.capture());
        assertEquals("did:issuer", captor.getValue().getOwnerId());
        assertEquals(List.of("name", "degree"), captor.getValue().getAttrNames());
        assertEquals(TX_TIME.toString(), captor.getValue().getCreatedAt());
        verify(eventEmitter).emit(eq(VdrEvents.SCHEMA_CREATED), any());
    }

    /**
     * Verifies creation rejects a malformed (wrong-prefix / non-UUIDv7) id before ledger access.
     */
    @Test
    void createSchemaRejectsMalformedId() {
        assertThrows(
                VdrException.class,
                () ->
                        schemaService.createSchema(
                                "did:issuer",
                                "schema:vdr:not-a-uuid-v7",
                                "did:issuer",
                                "name",
                                "1.0",
                                "[\"name\"]"));
    }

    /** Verifies schema creation fails when issuer DID is not active. */
    @Test
    void createSchemaRejectsInactiveIssuer() {
        when(schemaRegistry.exists(SCHEMA_ID)).thenReturn(false);
        final DidDocument issuer = new DidDocument();
        issuer.setId("did:issuer");
        issuer.setStatus(RecordStatus.INACTIVE);
        when(didRegistry.get("did:issuer")).thenReturn(issuer);

        final String attrNamesJson = "[\"name\"]";
        assertThrows(
                VdrException.class,
                () ->
                        schemaService.createSchema(
                                "did:issuer",
                                SCHEMA_ID,
                                "did:issuer",
                                "name",
                                "1.0",
                                attrNamesJson));
    }

    /** Verifies schema creation rejects signer DID mismatched with issuer DID. */
    @Test
    void createSchemaRejectsSignerMismatch() {
        // when(schemaRegistry.exists(SCHEMA_ID)).thenReturn(false);

        assertThrows(
                VdrException.class,
                () ->
                        schemaService.createSchema(
                                "did:other", SCHEMA_ID, "did:issuer", "name", "1.0", "[\"name\"]"));
    }
}
