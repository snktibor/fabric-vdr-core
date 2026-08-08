package com.example.vdr.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.vdr.exception.VdrException;
import com.example.vdr.ledger.ChaincodeEventEmitter;
import com.example.vdr.ledger.CredentialDefinitionRegistry;
import com.example.vdr.ledger.DidRegistry;
import com.example.vdr.ledger.SchemaRegistry;
import com.example.vdr.model.CredentialDefinitionRecord;
import com.example.vdr.model.DidDocument;
import com.example.vdr.model.RecordStatus;
import com.example.vdr.model.SchemaRecord;
import java.time.Instant;
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

    private static final String ISSUER_DID = "did:vdr:018f6c02-3a5e-7cbb-9d3e-5f2a4b6c8d0e";

    private static final String OTHER_DID = "did:vdr:018f6c09-2c55-7d61-b4a8-6f03e19b7c22";

    private static final String SCHEMA_ID =
            ISSUER_DID + "/anoncreds/v2/SCHEMA/018f6c05-1b22-7aa0-8c31-9e77d0f6a4b1";

    private static final String CRED_DEF_ID =
            ISSUER_DID + "/anoncreds/v2/PUBLIC_CRED_DEF/018f6c06-4d13-7b58-b0e2-3a91c4d7e2f0";

    @Mock private CredentialDefinitionRegistry credDefRegistry;

    @Mock private SchemaRegistry schemaRegistry;

    @Mock private DidRegistry didRegistry;

    @Mock private ChaincodeEventEmitter eventEmitter;

    private CredentialDefinitionService credentialDefinitionService;

    /** Initializes service under test with mocked registries and real validators. */
    @BeforeEach
    void setUp() {
        credentialDefinitionService =
                new CredentialDefinitionService(
                        credDefRegistry,
                        schemaRegistry,
                        didRegistry,
                        new ValidationService(),
                        eventEmitter,
                        Instant.parse("2026-07-12T10:15:30Z"));
    }

    /** Verifies newly created credential definitions persist signer DID ownership. */
    @Test
    void createCredDefSetsOwner() {
        when(credDefRegistry.exists(CRED_DEF_ID)).thenReturn(false);
        when(schemaRegistry.get(SCHEMA_ID)).thenReturn(new SchemaRecord());
        final DidDocument issuer = new DidDocument();
        issuer.setId(ISSUER_DID);
        issuer.setStatus(RecordStatus.ACTIVE);
        when(didRegistry.get(ISSUER_DID)).thenReturn(issuer);

        final String publicMetadata = "{\"type\":\"BBS\"}";
        credentialDefinitionService.createCredDef(
                ISSUER_DID,
                CRED_DEF_ID,
                ISSUER_DID,
                SCHEMA_ID,
                VdrTypes.CRED_DEF_BBS_V1,
                "TAG",
                publicMetadata);

        final ArgumentCaptor<CredentialDefinitionRecord> captor =
                ArgumentCaptor.forClass(CredentialDefinitionRecord.class);
        verify(credDefRegistry).put(anyString(), captor.capture());
        assertEquals(ISSUER_DID, captor.getValue().getOwnerId());
    }

    /**
     * Verifies creation rejects an id whose object segment is not a UUIDv7 before ledger access.
     */
    @Test
    void createCredDefRejectsMalformedId() {
        assertThrows(
                VdrException.class,
                () ->
                        credentialDefinitionService.createCredDef(
                                ISSUER_DID,
                                ISSUER_DID + "/anoncreds/v2/PUBLIC_CRED_DEF/not-a-uuid-v7",
                                ISSUER_DID,
                                SCHEMA_ID,
                                VdrTypes.CRED_DEF_BBS_V1,
                                "TAG",
                                "{\"type\":\"BBS\"}"));
    }

    /** Verifies creation fails when the referenced schema does not exist. */
    @Test
    void createCredDefRejectsMissingSchema() {
        when(credDefRegistry.exists(CRED_DEF_ID)).thenReturn(false);
        when(schemaRegistry.get(SCHEMA_ID)).thenReturn(null);

        final String publicMetadata = "{\"type\":\"BBS\"}";
        assertThrows(
                VdrException.class,
                () ->
                        credentialDefinitionService.createCredDef(
                                ISSUER_DID,
                                CRED_DEF_ID,
                                ISSUER_DID,
                                SCHEMA_ID,
                                VdrTypes.CRED_DEF_BBS_V1,
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
                                OTHER_DID,
                                CRED_DEF_ID,
                                ISSUER_DID,
                                SCHEMA_ID,
                                VdrTypes.CRED_DEF_BBS_V1,
                                "TAG",
                                "{\"type\":\"BBS\"}"));
    }

    /** Verifies non-allowlisted credential definition types are rejected. */
    @Test
    void createCredDefRejectsUnknownType() {
        final VdrException exception =
                assertThrows(
                        VdrException.class,
                        () ->
                                credentialDefinitionService.createCredDef(
                                        ISSUER_DID,
                                        CRED_DEF_ID,
                                        ISSUER_DID,
                                        SCHEMA_ID,
                                        "CL",
                                        "TAG",
                                        "{}"));
        assertEquals("INVALID_ARGUMENT", exception.getErrorCode());
    }
}
