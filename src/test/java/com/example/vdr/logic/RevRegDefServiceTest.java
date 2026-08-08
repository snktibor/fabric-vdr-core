package com.example.vdr.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.vdr.TestVectors;
import com.example.vdr.exception.VdrException;
import com.example.vdr.ledger.ChaincodeEventEmitter;
import com.example.vdr.ledger.CredentialDefinitionRegistry;
import com.example.vdr.ledger.DidRegistry;
import com.example.vdr.ledger.RevRegDefRegistry;
import com.example.vdr.model.CredentialDefinitionRecord;
import com.example.vdr.model.DidDocument;
import com.example.vdr.model.RecordStatus;
import com.example.vdr.model.RevRegDefRecord;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link RevRegDefService} creation semantics.
 *
 * <p>The suite verifies owner attribution on persistence and fail-fast rejection when referenced
 * credential definition dependencies are missing or inconsistent with the requesting issuer.
 */
@SuppressWarnings("java:S1192")
@ExtendWith(MockitoExtension.class)
class RevRegDefServiceTest {

    private static final String ISSUER_DID = "did:vdr:018f6c02-3a5e-7cbb-9d3e-5f2a4b6c8d0e";

    private static final String OTHER_DID = "did:vdr:018f6c09-2c55-7d61-b4a8-6f03e19b7c22";

    private static final String CRED_DEF_ID =
            ISSUER_DID + "/anoncreds/v2/PUBLIC_CRED_DEF/018f6c06-4d13-7b58-b0e2-3a91c4d7e2f0";

    private static final String REV_REG_DEF_ID =
            ISSUER_DID + "/anoncreds/v2/REV_REG_DEF/018f6c07-9e04-7ff1-a3c6-71b28e05d9a4";

    @Mock private RevRegDefRegistry revRegDefRegistry;

    @Mock private CredentialDefinitionRegistry credDefRegistry;

    @Mock private DidRegistry didRegistry;

    @Mock private ChaincodeEventEmitter eventEmitter;

    private RevRegDefService revRegDefService;

    /** Initializes service under test with mocked registries and real validators. */
    @BeforeEach
    void setUp() {
        revRegDefService =
                new RevRegDefService(
                        revRegDefRegistry,
                        credDefRegistry,
                        didRegistry,
                        new ValidationService(),
                        eventEmitter,
                        Instant.parse("2026-07-12T10:15:30Z"));
    }

    /** Verifies newly created revocation registry definitions persist signer DID ownership. */
    @Test
    void createRevRegDefSetsOwner() {
        when(revRegDefRegistry.exists(REV_REG_DEF_ID)).thenReturn(false);
        final CredentialDefinitionRecord credDef = new CredentialDefinitionRecord();
        credDef.setIssuerId(ISSUER_DID);
        when(credDefRegistry.get(CRED_DEF_ID)).thenReturn(credDef);
        final DidDocument issuer = new DidDocument();
        issuer.setId(ISSUER_DID);
        issuer.setStatus(RecordStatus.ACTIVE);
        when(didRegistry.get(ISSUER_DID)).thenReturn(issuer);

        final String publicKeys = TestVectors.REVOCATION_PUBLIC_KEYS_JSON;
        revRegDefService.createRevRegDef(
                ISSUER_DID,
                REV_REG_DEF_ID,
                ISSUER_DID,
                CRED_DEF_ID,
                VdrTypes.REV_REG_VB20_V1,
                publicKeys,
                100L);

        final ArgumentCaptor<RevRegDefRecord> captor =
                ArgumentCaptor.forClass(RevRegDefRecord.class);
        verify(revRegDefRegistry).put(anyString(), captor.capture());
        assertEquals(ISSUER_DID, captor.getValue().getOwnerId());
        assertEquals(100L, captor.getValue().getMaxCredNum());
    }

    /**
     * Verifies creation rejects an id whose object segment is not a UUIDv7 before ledger access.
     */
    @Test
    void createRevRegDefRejectsMalformedId() {
        assertThrows(
                VdrException.class,
                () ->
                        revRegDefService.createRevRegDef(
                                ISSUER_DID,
                                ISSUER_DID + "/anoncreds/v2/REV_REG_DEF/not-a-uuid-v7",
                                ISSUER_DID,
                                CRED_DEF_ID,
                                VdrTypes.REV_REG_VB20_V1,
                                TestVectors.REVOCATION_PUBLIC_KEYS_JSON,
                                100L));
    }

    /** Verifies creation fails when the referenced credential definition does not exist. */
    @Test
    void createRevRegDefRejectsMissingCredDef() {
        when(revRegDefRegistry.exists(REV_REG_DEF_ID)).thenReturn(false);
        when(credDefRegistry.get(CRED_DEF_ID)).thenReturn(null);

        final String publicKeys = TestVectors.REVOCATION_PUBLIC_KEYS_JSON;
        assertThrows(
                VdrException.class,
                () ->
                        revRegDefService.createRevRegDef(
                                ISSUER_DID,
                                REV_REG_DEF_ID,
                                ISSUER_DID,
                                CRED_DEF_ID,
                                VdrTypes.REV_REG_VB20_V1,
                                publicKeys,
                                100L));
    }

    /** Verifies creation fails when signer DID differs from issuer DID. */
    @Test
    void createRevRegDefRejectsSignerMismatch() {
        assertThrows(
                VdrException.class,
                () ->
                        revRegDefService.createRevRegDef(
                                OTHER_DID,
                                REV_REG_DEF_ID,
                                ISSUER_DID,
                                CRED_DEF_ID,
                                VdrTypes.REV_REG_VB20_V1,
                                TestVectors.REVOCATION_PUBLIC_KEYS_JSON,
                                100L));
    }

    /** Verifies creation fails when issuer differs from the referenced credential definition. */
    @Test
    void createRevRegDefRejectsCredDefIssuerMismatch() {
        when(revRegDefRegistry.exists(REV_REG_DEF_ID)).thenReturn(false);
        final CredentialDefinitionRecord credDef = new CredentialDefinitionRecord();
        credDef.setIssuerId(OTHER_DID);
        when(credDefRegistry.get(CRED_DEF_ID)).thenReturn(credDef);

        assertThrows(
                VdrException.class,
                () ->
                        revRegDefService.createRevRegDef(
                                ISSUER_DID,
                                REV_REG_DEF_ID,
                                ISSUER_DID,
                                CRED_DEF_ID,
                                VdrTypes.REV_REG_VB20_V1,
                                TestVectors.REVOCATION_PUBLIC_KEYS_JSON,
                                100L));
    }

    /** Verifies non-allowlisted revocation scheme identifiers are rejected. */
    @Test
    void createRevRegDefRejectsUnknownType() {
        final VdrException exception =
                assertThrows(
                        VdrException.class,
                        () ->
                                revRegDefService.createRevRegDef(
                                        ISSUER_DID,
                                        REV_REG_DEF_ID,
                                        ISSUER_DID,
                                        CRED_DEF_ID,
                                        "CL_ACCUM",
                                        TestVectors.REVOCATION_PUBLIC_KEYS_JSON,
                                        100L));
        assertEquals("INVALID_ARGUMENT", exception.getErrorCode());
    }

    /** Verifies public keys payloads with extra fields are rejected. */
    @Test
    void createRevRegDefRejectsExtraPublicKeyFields() {
        final String extraField =
                "{\"revocationVerifyingKey\":\"" + TestVectors.G2_GENERATOR + "\",\"other\":\"x\"}";
        final VdrException exception =
                assertThrows(
                        VdrException.class,
                        () ->
                                revRegDefService.createRevRegDef(
                                        ISSUER_DID,
                                        REV_REG_DEF_ID,
                                        ISSUER_DID,
                                        CRED_DEF_ID,
                                        VdrTypes.REV_REG_VB20_V1,
                                        extraField,
                                        100L));
        assertEquals("INVALID_ARGUMENT", exception.getErrorCode());
    }
}
