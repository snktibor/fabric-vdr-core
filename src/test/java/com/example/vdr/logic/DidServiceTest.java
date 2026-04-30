package com.example.vdr.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.vdr.exception.VdrException;
import com.example.vdr.ledger.DidRegistry;
import com.example.vdr.model.DidDocument;
import com.example.vdr.model.RecordStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link DidService} lifecycle and authorization rules.
 *
 * <p>Covers ownership propagation, duplicate prevention, and active-state checks.
 */
@SuppressWarnings("java:S1192")
@ExtendWith(MockitoExtension.class)
class DidServiceTest {

    @Mock private DidRegistry didRegistry;

    private DidService didService;

    /** Initializes service under test with mocked registry dependency. */
    @BeforeEach
    void setUp() {
        didService =
                new DidService(didRegistry, new AuthorizationService(), new ValidationService());
    }

    private static String didJson(final String did) {
        return "{"
                + "\"id\":\""
                + did
                + "\","
                + "\"controller\":\""
                + did
                + "\","
                + "\"verificationMethod\":[{"
                + "\"id\":\""
                + did
                + "#key-1\","
                + "\"type\":\"JsonWebKey2020\","
                + "\"controller\":\""
                + did
                + "\","
                + "\"publicKeyJwk\":{"
                + "\"kty\":\"EC\","
                + "\"crv\":\"P-256\","
                + "\"x\":\"f83OJ3D2xF4P6sY0j8B1Q9QXc7Tx8U7R8G5C7jS1m3A\","
                + "\"y\":\"x_FEzRu9q4hM9M4f6jQ9QvV4fru3c6JQnSg0dW4mN18\""
                + "}}]}";
    }

    /** Verifies created DID records persist DID ownership and active status. */
    @Test
    void createDidSetsOwnerAndStatus() {
        when(didRegistry.exists(anyString())).thenReturn(false);

        final String json = didJson("did:example:123");
        didService.createDid("did:example:123", json);

        final ArgumentCaptor<DidDocument> captor = ArgumentCaptor.forClass(DidDocument.class);
        verify(didRegistry).put(anyString(), captor.capture());
        final DidDocument stored = captor.getValue();
        assertEquals("did:example:123", stored.getOwnerId());
        assertEquals(RecordStatus.ACTIVE, stored.getStatus());
    }

    /** Verifies duplicate DID identifiers are rejected. */
    @Test
    void createDidRejectsDuplicate() {
        when(didRegistry.exists("did:example:123")).thenReturn(true);

        final String json = didJson("did:example:123");
        assertThrows(VdrException.class, () -> didService.createDid("did:example:123", json));
    }

    /** Verifies deactivation requires owner identity match. */
    @Test
    void deactivateDidRequiresOwner() {
        final DidDocument existing = new DidDocument();
        existing.setId("did:example:123");
        existing.setOwnerId("owner");
        existing.setStatus(RecordStatus.ACTIVE);
        when(didRegistry.get("did:example:123")).thenReturn(existing);

        assertThrows(
                VdrException.class, () -> didService.deactivateDid("other", "did:example:123"));
    }

    /** Verifies DID update preserves owner/status and writes replacement document. */
    @Test
    void updateDidActiveOwnerPersistsUpdatedDocument() {
        final DidDocument existing = new DidDocument();
        existing.setId("did:example:123");
        existing.setOwnerId("owner");
        existing.setStatus(RecordStatus.ACTIVE);
        when(didRegistry.get("did:example:123")).thenReturn(existing);

        final String json = didJson("did:example:123");
        didService.updateDid("owner", "did:example:123", json);

        final ArgumentCaptor<DidDocument> captor = ArgumentCaptor.forClass(DidDocument.class);
        verify(didRegistry).put(eq("did:example:123"), captor.capture());
        final DidDocument stored = captor.getValue();
        assertEquals("owner", stored.getOwnerId());
        assertEquals(RecordStatus.ACTIVE, stored.getStatus());
        assertEquals("did:example:123", stored.getController());
    }

    /** Verifies DID update rejects inactive records with authorization-style failure. */
    @Test
    void updateDidInactiveDidRejected() {
        final DidDocument existing = new DidDocument();
        existing.setId("did:example:123");
        existing.setOwnerId("owner");
        existing.setStatus(RecordStatus.INACTIVE);
        when(didRegistry.get("did:example:123")).thenReturn(existing);

        final String json = didJson("did:example:123");
        final VdrException exception =
                assertThrows(
                        VdrException.class,
                        () -> didService.updateDid("owner", "did:example:123", json));
        assertEquals("UNAUTHORIZED", exception.getErrorCode());
        assertTrue(exception.getMessage().toLowerCase().contains("authorization"));
    }

    /** Verifies active-state assertion fails for inactive DID records. */
    @Test
    void assertDidActiveRejectsInactive() {
        final DidDocument existing = new DidDocument();
        existing.setId("did:example:123");
        existing.setStatus(RecordStatus.INACTIVE);
        when(didRegistry.get("did:example:123")).thenReturn(existing);

        assertThrows(VdrException.class, () -> didService.assertDidActive("did:example:123"));
    }
}
