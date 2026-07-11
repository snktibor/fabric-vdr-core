package com.example.vdr.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.vdr.exception.VdrException;
import com.example.vdr.ledger.CredentialDefinitionRegistry;
import com.example.vdr.ledger.DidRegistry;
import com.example.vdr.ledger.RevRegDefRegistry;
import com.example.vdr.model.CredentialDefinitionRecord;
import com.example.vdr.model.DidDocument;
import com.example.vdr.model.RecordStatus;
import com.example.vdr.model.RevRegDefRecord;
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

    private static final String REV_REG_DEF_ID =
            "revregdef:vdr:018f6c02-3a5e-7cbb-9d3e-5f2a4b6c8d0e";

    @Mock private RevRegDefRegistry revRegDefRegistry;

    @Mock private CredentialDefinitionRegistry credDefRegistry;

    @Mock private DidRegistry didRegistry;

    private RevRegDefService revRegDefService;

    /** Initializes service under test with mocked registries and real validators. */
    @BeforeEach
    void setUp() {
        revRegDefService =
                new RevRegDefService(
                        revRegDefRegistry, credDefRegistry, didRegistry, new ValidationService());
    }

    /** Verifies newly created revocation registry definitions persist signer DID ownership. */
    @Test
    void createRevRegDefSetsOwner() {
        when(revRegDefRegistry.exists(REV_REG_DEF_ID)).thenReturn(false);
        final CredentialDefinitionRecord credDef = new CredentialDefinitionRecord();
        credDef.setIssuerId("did:issuer");
        when(credDefRegistry.get("creddef-1")).thenReturn(credDef);
        final DidDocument issuer = new DidDocument();
        issuer.setId("did:issuer");
        issuer.setStatus(RecordStatus.ACTIVE);
        when(didRegistry.get("did:issuer")).thenReturn(issuer);

        final String publicKeys = "{\"revocationVerifyingKey\":\"key-1\"}";
        revRegDefService.createRevRegDef(
                "did:issuer",
                REV_REG_DEF_ID,
                "did:issuer",
                "creddef-1",
                "ALLOSAUR",
                publicKeys,
                100L);

        final ArgumentCaptor<RevRegDefRecord> captor =
                ArgumentCaptor.forClass(RevRegDefRecord.class);
        verify(revRegDefRegistry).put(anyString(), captor.capture());
        assertEquals("did:issuer", captor.getValue().getOwnerId());
        assertEquals(100L, captor.getValue().getMaxCredNum());
    }

    /**
     * Verifies creation rejects a malformed (wrong-prefix / non-UUIDv7) id before ledger access.
     */
    @Test
    void createRevRegDefRejectsMalformedId() {
        assertThrows(
                VdrException.class,
                () ->
                        revRegDefService.createRevRegDef(
                                "did:issuer",
                                "revregdef:vdr:not-a-uuid-v7",
                                "did:issuer",
                                "creddef-1",
                                "ALLOSAUR",
                                "{\"revocationVerifyingKey\":\"key-1\"}",
                                100L));
    }

    /** Verifies creation fails when the referenced credential definition does not exist. */
    @Test
    void createRevRegDefRejectsMissingCredDef() {
        when(revRegDefRegistry.exists(REV_REG_DEF_ID)).thenReturn(false);
        when(credDefRegistry.get("creddef-1")).thenReturn(null);

        final String publicKeys = "{\"revocationVerifyingKey\":\"key-1\"}";
        assertThrows(
                VdrException.class,
                () ->
                        revRegDefService.createRevRegDef(
                                "did:issuer",
                                REV_REG_DEF_ID,
                                "did:issuer",
                                "creddef-1",
                                "ALLOSAUR",
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
                                "did:other",
                                REV_REG_DEF_ID,
                                "did:issuer",
                                "creddef-1",
                                "ALLOSAUR",
                                "{\"revocationVerifyingKey\":\"key-1\"}",
                                100L));
    }

    /** Verifies creation fails when issuer differs from the referenced credential definition. */
    @Test
    void createRevRegDefRejectsCredDefIssuerMismatch() {
        when(revRegDefRegistry.exists(REV_REG_DEF_ID)).thenReturn(false);
        final CredentialDefinitionRecord credDef = new CredentialDefinitionRecord();
        credDef.setIssuerId("did:someone-else");
        when(credDefRegistry.get("creddef-1")).thenReturn(credDef);

        assertThrows(
                VdrException.class,
                () ->
                        revRegDefService.createRevRegDef(
                                "did:issuer",
                                REV_REG_DEF_ID,
                                "did:issuer",
                                "creddef-1",
                                "ALLOSAUR",
                                "{\"revocationVerifyingKey\":\"key-1\"}",
                                100L));
    }
}
