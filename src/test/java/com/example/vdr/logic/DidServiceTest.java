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

    /** Verifies created DID records persist caller ownership and active status. */
    @Test
    void createDidSetsOwnerAndStatus() {
        when(didRegistry.exists(anyString())).thenReturn(false);

        final String json = "{\"id\":\"did:example:123\",\"controller\":\"did:example:123\"}";
        didService.createDid("caller", "did:example:123", json);

        final ArgumentCaptor<DidDocument> captor = ArgumentCaptor.forClass(DidDocument.class);
        verify(didRegistry).put(anyString(), captor.capture());
        final DidDocument stored = captor.getValue();
        assertEquals("caller", stored.getOwnerId());
        assertEquals(RecordStatus.ACTIVE, stored.getStatus());
    }

    /** Verifies duplicate DID identifiers are rejected. */
    @Test
    void createDidRejectsDuplicate() {
        when(didRegistry.exists("did:example:123")).thenReturn(true);

        final String json = "{\"id\":\"did:example:123\",\"controller\":\"did:example:123\"}";
        assertThrows(
                VdrException.class, () -> didService.createDid("caller", "did:example:123", json));
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

        final String json =
                "{\"id\":\"did:example:123\",\"controller\":\"did:example:controller\"}";
        didService.updateDid("owner", "did:example:123", json);

        final ArgumentCaptor<DidDocument> captor = ArgumentCaptor.forClass(DidDocument.class);
        verify(didRegistry).put(eq("did:example:123"), captor.capture());
        final DidDocument stored = captor.getValue();
        assertEquals("owner", stored.getOwnerId());
        assertEquals(RecordStatus.ACTIVE, stored.getStatus());
        assertEquals("did:example:controller", stored.getController());
    }

    /** Verifies DID update rejects inactive records with authorization-style failure. */
    @Test
    void updateDidInactiveDidRejected() {
        final DidDocument existing = new DidDocument();
        existing.setId("did:example:123");
        existing.setOwnerId("owner");
        existing.setStatus(RecordStatus.INACTIVE);
        when(didRegistry.get("did:example:123")).thenReturn(existing);

        final String json = "{\"id\":\"did:example:123\"}";
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
