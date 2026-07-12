package com.example.vdr.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.vdr.exception.VdrException;
import com.example.vdr.ledger.ChaincodeEventEmitter;
import com.example.vdr.ledger.KeyRevision;
import com.example.vdr.ledger.RevRegDefRegistry;
import com.example.vdr.ledger.RevRegEntryRegistry;
import com.example.vdr.ledger.VdrEvents;
import com.example.vdr.model.RevRegDefRecord;
import com.example.vdr.model.RevRegEntryRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link RevRegEntryService} creation and mutation semantics.
 *
 * <p>The suite verifies genesis versioning, owner attribution copied from the parent revocation
 * registry definition, owner-only authorization on both create and update, and fail-fast rejection
 * of malformed ids, missing parents, and duplicate entries.
 */
@SuppressWarnings("java:S1192")
@ExtendWith(MockitoExtension.class)
class RevRegEntryServiceTest {

    private static final String REV_REG_ENTRY_ID =
            "revregentry:vdr:018f6c02-3a5e-7cbb-9d3e-5f2a4b6c8d0e";
    private static final Instant TX_TIME = Instant.parse("2026-07-12T10:15:30Z");

    @Mock private RevRegEntryRegistry revRegEntryRegistry;

    @Mock private RevRegDefRegistry revRegDefRegistry;

    @Mock private ChaincodeEventEmitter eventEmitter;

    private RevRegEntryService revRegEntryService;

    /** Initializes service under test with mocked registries and real validators. */
    @BeforeEach
    void setUp() {
        revRegEntryService =
                new RevRegEntryService(
                        revRegEntryRegistry,
                        revRegDefRegistry,
                        new ValidationService(),
                        eventEmitter,
                        TX_TIME);
    }

    /** Creates a parent revocation registry definition owned by the given DID. */
    private RevRegDefRecord parentOwnedBy(final String ownerDid) {
        final RevRegDefRecord parent = new RevRegDefRecord();
        parent.setId("revregdef-1");
        parent.setOwnerId(ownerDid);
        return parent;
    }

    /** Verifies genesis entries persist parent ownership, version 0, and the verbatim value. */
    @Test
    void createRevRegEntrySetsParentOwnerAndGenesisVersion() {
        when(revRegDefRegistry.get("revregdef-1")).thenReturn(parentOwnedBy("did:issuer"));
        when(revRegEntryRegistry.exists("revregdef-1")).thenReturn(false);

        revRegEntryService.createRevRegEntry(
                "did:issuer", REV_REG_ENTRY_ID, "revregdef-1", "accum-genesis");

        final ArgumentCaptor<RevRegEntryRecord> captor =
                ArgumentCaptor.forClass(RevRegEntryRecord.class);
        verify(revRegEntryRegistry).put(eq("revregdef-1"), captor.capture());
        assertEquals("did:issuer", captor.getValue().getOwnerId());
        assertEquals(0L, captor.getValue().getVersion());
        assertEquals("accum-genesis", captor.getValue().getAccumValue());
        assertEquals(TX_TIME.toString(), captor.getValue().getCreatedAt());
        assertEquals(TX_TIME.toString(), captor.getValue().getUpdatedAt());
    }

    /** Verifies genesis creation emits the accumulator event carrying the full payload. */
    @Test
    void createRevRegEntryEmitsCreatedEventWithAccumulator() {
        when(revRegDefRegistry.get("revregdef-1")).thenReturn(parentOwnedBy("did:issuer"));
        when(revRegEntryRegistry.exists("revregdef-1")).thenReturn(false);

        revRegEntryService.createRevRegEntry(
                "did:issuer", REV_REG_ENTRY_ID, "revregdef-1", "accum-genesis");

        final ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventEmitter).emit(eq(VdrEvents.REV_REG_ENTRY_CREATED), payloadCaptor.capture());
        final Map<?, ?> payload = (Map<?, ?>) payloadCaptor.getValue();
        assertEquals("revregdef-1", payload.get("revRegDefId"));
        assertEquals("accum-genesis", payload.get("accumValue"));
        assertEquals(0L, payload.get("version"));
        assertEquals(TX_TIME.toString(), payload.get("txTime"));
    }

    /**
     * Verifies creation rejects a malformed (wrong-prefix / non-UUIDv7) id as a domain exception.
     */
    @Test
    void createRevRegEntryRejectsMalformedId() {
        final VdrException wrongPrefix =
                assertThrows(
                        VdrException.class,
                        () ->
                                revRegEntryService.createRevRegEntry(
                                        "did:issuer",
                                        "revregdef:vdr:018f6c02-3a5e-7cbb-9d3e-5f2a4b6c8d0e",
                                        "revregdef-1",
                                        "accum-genesis"));
        assertEquals("INVALID_ARGUMENT", wrongPrefix.getErrorCode());

        final VdrException notUuidV7 =
                assertThrows(
                        VdrException.class,
                        () ->
                                revRegEntryService.createRevRegEntry(
                                        "did:issuer",
                                        "revregentry:vdr:not-a-uuid-v7",
                                        "revregdef-1",
                                        "accum-genesis"));
        assertEquals("INVALID_ARGUMENT", notUuidV7.getErrorCode());
    }

    /** Verifies creation fails when the parent revocation registry definition does not exist. */
    @Test
    void createRevRegEntryRejectsMissingParent() {
        when(revRegDefRegistry.get("revregdef-1")).thenReturn(null);

        assertThrows(
                VdrException.class,
                () ->
                        revRegEntryService.createRevRegEntry(
                                "did:issuer", REV_REG_ENTRY_ID, "revregdef-1", "accum-genesis"));
    }

    /** Verifies creation fails when the signer does not own the parent definition. */
    @Test
    void createRevRegEntryRejectsNonOwnerSigner() {
        when(revRegDefRegistry.get("revregdef-1")).thenReturn(parentOwnedBy("did:issuer"));

        final VdrException exception =
                assertThrows(
                        VdrException.class,
                        () ->
                                revRegEntryService.createRevRegEntry(
                                        "did:other",
                                        REV_REG_ENTRY_ID,
                                        "revregdef-1",
                                        "accum-genesis"));
        assertEquals("UNAUTHORIZED", exception.getErrorCode());
        verify(revRegEntryRegistry, never()).put(anyString(), any());
    }

    /** Verifies creation fails when an entry already exists for the definition. */
    @Test
    void createRevRegEntryRejectsDuplicateForDefinition() {
        when(revRegDefRegistry.get("revregdef-1")).thenReturn(parentOwnedBy("did:issuer"));
        when(revRegEntryRegistry.exists("revregdef-1")).thenReturn(true);

        final VdrException exception =
                assertThrows(
                        VdrException.class,
                        () ->
                                revRegEntryService.createRevRegEntry(
                                        "did:issuer",
                                        REV_REG_ENTRY_ID,
                                        "revregdef-1",
                                        "accum-genesis"));
        assertEquals("ALREADY_EXISTS", exception.getErrorCode());
    }

    /** Verifies updates replace the accumulator value verbatim and increment the version. */
    @Test
    void updateRevRegEntryReplacesValueAndIncrementsVersion() {
        final RevRegEntryRecord existing =
                new RevRegEntryRecord(
                        REV_REG_ENTRY_ID, "revregdef-1", "accum-genesis", 3L, "did:issuer");
        when(revRegEntryRegistry.get("revregdef-1")).thenReturn(existing);
        when(revRegDefRegistry.get("revregdef-1")).thenReturn(parentOwnedBy("did:issuer"));

        revRegEntryService.updateRevRegEntry("did:issuer", "revregdef-1", "accum-next");

        final ArgumentCaptor<RevRegEntryRecord> captor =
                ArgumentCaptor.forClass(RevRegEntryRecord.class);
        verify(revRegEntryRegistry).put(eq("revregdef-1"), captor.capture());
        assertEquals("accum-next", captor.getValue().getAccumValue());
        assertEquals(4L, captor.getValue().getVersion());
        assertEquals("did:issuer", captor.getValue().getOwnerId());
        assertEquals(TX_TIME.toString(), captor.getValue().getUpdatedAt());
    }

    /** Verifies accumulator updates emit the witness-refresh event with the new value. */
    @Test
    void updateRevRegEntryEmitsUpdatedEventWithNewAccumulator() {
        final RevRegEntryRecord existing =
                new RevRegEntryRecord(
                        REV_REG_ENTRY_ID, "revregdef-1", "accum-genesis", 0L, "did:issuer");
        when(revRegEntryRegistry.get("revregdef-1")).thenReturn(existing);
        when(revRegDefRegistry.get("revregdef-1")).thenReturn(parentOwnedBy("did:issuer"));

        revRegEntryService.updateRevRegEntry("did:issuer", "revregdef-1", "accum-next");

        final ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventEmitter).emit(eq(VdrEvents.REV_REG_ENTRY_UPDATED), payloadCaptor.capture());
        final Map<?, ?> payload = (Map<?, ?>) payloadCaptor.getValue();
        assertEquals("revregdef-1", payload.get("revRegDefId"));
        assertEquals("accum-next", payload.get("accumValue"));
        assertEquals(1L, payload.get("version"));
        assertEquals(TX_TIME.toString(), payload.get("txTime"));
    }

    /** Verifies updates from a non-owner signer are rejected without any ledger write. */
    @Test
    void updateRevRegEntryRejectsNonOwnerSigner() {
        final RevRegEntryRecord existing =
                new RevRegEntryRecord(
                        REV_REG_ENTRY_ID, "revregdef-1", "accum-genesis", 0L, "did:issuer");
        when(revRegEntryRegistry.get("revregdef-1")).thenReturn(existing);
        when(revRegDefRegistry.get("revregdef-1")).thenReturn(parentOwnedBy("did:issuer"));

        final VdrException exception =
                assertThrows(
                        VdrException.class,
                        () ->
                                revRegEntryService.updateRevRegEntry(
                                        "did:other", "revregdef-1", "accum-hijacked"));
        assertEquals("UNAUTHORIZED", exception.getErrorCode());
        verify(revRegEntryRegistry, never()).put(anyString(), any());
        verify(eventEmitter, never()).emit(anyString(), any());
    }

    /** Verifies history resolution delegates to the registry and preserves revision order. */
    @Test
    void getRevRegEntryHistoryDelegatesToRegistry() {
        final RevRegEntryRecord genesis =
                new RevRegEntryRecord(
                        REV_REG_ENTRY_ID, "revregdef-1", "accum-genesis", 0L, "did:issuer");
        final List<KeyRevision<RevRegEntryRecord>> revisions =
                List.of(new KeyRevision<>("tx-1", TX_TIME, false, genesis));
        when(revRegEntryRegistry.history("revregdef-1")).thenReturn(revisions);

        final List<KeyRevision<RevRegEntryRecord>> result =
                revRegEntryService.getRevRegEntryHistory("revregdef-1");

        assertEquals(1, result.size());
        assertEquals("tx-1", result.get(0).getTxId());
        assertEquals("accum-genesis", result.get(0).getValue().getAccumValue());
    }

    /** Verifies history rejects a blank definition id before touching the registry. */
    @Test
    void getRevRegEntryHistoryRejectsBlankId() {
        final VdrException exception =
                assertThrows(
                        VdrException.class, () -> revRegEntryService.getRevRegEntryHistory(" "));
        assertEquals("INVALID_ARGUMENT", exception.getErrorCode());
    }

    /** Verifies updates fail when no entry exists for the definition. */
    @Test
    void updateRevRegEntryRejectsMissingEntry() {
        when(revRegEntryRegistry.get("revregdef-1")).thenReturn(null);

        assertThrows(
                VdrException.class,
                () ->
                        revRegEntryService.updateRevRegEntry(
                                "did:issuer", "revregdef-1", "accum-next"));
    }
}
