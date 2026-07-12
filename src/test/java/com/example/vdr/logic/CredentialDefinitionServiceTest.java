package com.example.vdr.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.vdr.exception.VdrException;
import com.example.vdr.ledger.CredentialDefinitionRegistry;
import com.example.vdr.ledger.DidRegistry;
import com.example.vdr.ledger.SchemaRegistry;
import com.example.vdr.model.CredentialDefinitionRecord;
import com.example.vdr.model.DidDocument;
import com.example.vdr.model.RecordStatus;
import com.example.vdr.model.SchemaRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CredentialDefinitionService} creation semantics.
 *
 * <p>The suite verifies owner attribution on persistence and fail-fast rejection when referenced
 * schema dependencies are missing.
 */
@SuppressWarnings("java:S1192")
@ExtendWith(MockitoExtension.class)
class CredentialDefinitionServiceTest {

    private static final String CRED_DEF_ID = "creddef:vdr:018f6c02-3a5e-7cbb-9d3e-5f2a4b6c8d0e";

    @Mock private CredentialDefinitionRegistry credDefRegistry;

    @Mock private SchemaRegistry schemaRegistry;

    @Mock private DidRegistry didRegistry;

    private CredentialDefinitionService credentialDefinitionService;

    /** Initializes service under test with mocked registries and real validators. */
    @BeforeEach
    void setUp() {
        credentialDefinitionService =
                new CredentialDefinitionService(
                        credDefRegistry, schemaRegistry, didRegistry, new ValidationService());
    }

    /** Verifies newly created credential definitions persist signer DID ownership. */
    @Test
    void createCredDefSetsOwner() {
        when(credDefRegistry.exists(CRED_DEF_ID)).thenReturn(false);
        when(schemaRegistry.get("schema-1")).thenReturn(new SchemaRecord());
        final DidDocument issuer = new DidDocument();
        issuer.setId("did:issuer");
        issuer.setStatus(RecordStatus.ACTIVE);
        when(didRegistry.get("did:issuer")).thenReturn(issuer);

        final String publicMetadata = "{\"type\":\"BBS\"}";
        credentialDefinitionService.createCredDef(
                "did:issuer", CRED_DEF_ID, "did:issuer", "schema-1", "BBS", "TAG", publicMetadata);

        final ArgumentCaptor<CredentialDefinitionRecord> captor =
                ArgumentCaptor.forClass(CredentialDefinitionRecord.class);
        verify(credDefRegistry).put(anyString(), captor.capture());
        assertEquals("did:issuer", captor.getValue().getOwnerId());
    }

    /**
     * Verifies creation rejects a malformed (wrong-prefix / non-UUIDv7) id before ledger access.
     */
    @Test
    void createCredDefRejectsMalformedId() {
        assertThrows(
                VdrException.class,
                () ->
                        credentialDefinitionService.createCredDef(
                                "did:issuer",
                                "creddef:vdr:not-a-uuid-v7",
                                "did:issuer",
                                "schema-1",
                                "BBS",
                                "TAG",
                                "{\"type\":\"BBS\"}"));
    }

    /** Verifies creation fails when the referenced schema does not exist. */
    @Test
    void createCredDefRejectsMissingSchema() {
        when(credDefRegistry.exists(CRED_DEF_ID)).thenReturn(false);
        when(schemaRegistry.get("schema-1")).thenReturn(null);

        final String publicMetadata = "{\"type\":\"BBS\"}";
        assertThrows(
                VdrException.class,
                () ->
                        credentialDefinitionService.createCredDef(
                                "did:issuer",
                                CRED_DEF_ID,
                                "did:issuer",
                                "schema-1",
                                "BBS",
                                "TAG",
                                publicMetadata));
    }

    /** Verifies creation fails when signer DID differs from issuer DID. */
    @Test
    void createCredDefRejectsSignerMismatch() {
        assertThrows(
                VdrException.class,
                () ->
                        credentialDefinitionService.createCredDef(
                                "did:other",
                                CRED_DEF_ID,
                                "did:issuer",
                                "schema-1",
                                "BBS",
                                "TAG",
                                "{\"type\":\"BBS\"}"));
    }
}
