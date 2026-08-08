package com.example.vdr.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.vdr.TestVectors;
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
 * <p>The suite verifies genesis versioning, owner-only authorization on both create and update
 * against the parent revocation registry definition's recorded owner, and fail-fast rejection of
 * malformed accumulator values, missing parents, and duplicate entries.
 */
@SuppressWarnings("java:S1192")
@ExtendWith(MockitoExtension.class)
class RevRegEntryServiceTest {

    private static final String ISSUER_DID = "did:vdr:018f6c02-3a5e-7cbb-9d3e-5f2a4b6c8d0e";
    private static final String OTHER_DID = "did:vdr:018f6c09-2c55-7d61-b4a8-6f03e19b7c22";
    private static final String REV_REG_DEF_ID =
            ISSUER_DID + "/anoncreds/v2/REV_REG_DEF/018f6c07-9e04-7ff1-a3c6-71b28e05d9a4";
    private static final Instant TX_TIME = Instant.parse("2026-07-12T10:15:30Z");
    private static final String GENESIS_ACCUM = TestVectors.G1_GENERATOR;
    private static final String NEXT_ACCUM = TestVectors.G1_TIMES_2;
    private static final String HIJACK_ACCUM = TestVectors.G1_TIMES_3;

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
        parent.setId(REV_REG_DEF_ID);
        parent.setOwnerId(ownerDid);
        return parent;
    }

    /** Verifies genesis entries persist version 0, the verbatim value, and audit timestamps. */
    @Test
    void createRevRegEntrySetsGenesisVersionAndTimestamps() {
        when(revRegDefRegistry.get(REV_REG_DEF_ID)).thenReturn(parentOwnedBy(ISSUER_DID));
        when(revRegEntryRegistry.exists(REV_REG_DEF_ID)).thenReturn(false);

        revRegEntryService.createRevRegEntry(ISSUER_DID, REV_REG_DEF_ID, GENESIS_ACCUM);

        final ArgumentCaptor<RevRegEntryRecord> captor =
                ArgumentCaptor.forClass(RevRegEntryRecord.class);
        verify(revRegEntryRegistry).put(eq(REV_REG_DEF_ID), captor.capture());
        assertEquals(REV_REG_DEF_ID, captor.getValue().getRevRegDefId());
        assertEquals(0L, captor.getValue().getVersion());
        assertEquals(GENESIS_ACCUM, captor.getValue().getAccumValue());
        assertEquals(TX_TIME.toString(), captor.getValue().getCreatedAt());
        assertEquals(TX_TIME.toString(), captor.getValue().getUpdatedAt());
    }

    /** Verifies genesis creation emits the accumulator event carrying the full payload. */
    @Test
    void createRevRegEntryEmitsCreatedEventWithAccumulator() {
        when(revRegDefRegistry.get(REV_REG_DEF_ID)).thenReturn(parentOwnedBy(ISSUER_DID));
        when(revRegEntryRegistry.exists(REV_REG_DEF_ID)).thenReturn(false);

        revRegEntryService.createRevRegEntry(ISSUER_DID, REV_REG_DEF_ID, GENESIS_ACCUM);

        final ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventEmitter).emit(eq(VdrEvents.REV_REG_ENTRY_CREATED), payloadCaptor.capture());
        final Map<?, ?> payload = (Map<?, ?>) payloadCaptor.getValue();
        assertEquals(REV_REG_DEF_ID, payload.get("revRegDefId"));
        assertEquals(GENESIS_ACCUM, payload.get("accumValue"));
        assertEquals(0L, payload.get("version"));
        assertEquals(3, payload.size());
    }

    /** Verifies creation fails when the parent revocation registry definition does not exist. */
    @Test
    void createRevRegEntryRejectsMissingParent() {
        when(revRegDefRegistry.get(REV_REG_DEF_ID)).thenReturn(null);

        assertThrows(
                VdrException.class,
                () ->
                        revRegEntryService.createRevRegEntry(
                                ISSUER_DID, REV_REG_DEF_ID, GENESIS_ACCUM));
    }

    /** Verifies creation fails when the signer does not own the parent definition. */
    @Test
    void createRevRegEntryRejectsNonOwnerSigner() {
        when(revRegDefRegistry.get(REV_REG_DEF_ID)).thenReturn(parentOwnedBy(ISSUER_DID));

        final VdrException exception =
                assertThrows(
                        VdrException.class,
                        () ->
                                revRegEntryService.createRevRegEntry(
                                        OTHER_DID, REV_REG_DEF_ID, GENESIS_ACCUM));
        assertEquals("UNAUTHORIZED", exception.getErrorCode());
        verify(revRegEntryRegistry, never()).put(anyString(), any());
    }

    /** Verifies creation fails when an entry already exists for the definition. */
    @Test
    void createRevRegEntryRejectsDuplicateForDefinition() {
        when(revRegDefRegistry.get(REV_REG_DEF_ID)).thenReturn(parentOwnedBy(ISSUER_DID));
        when(revRegEntryRegistry.exists(REV_REG_DEF_ID)).thenReturn(true);

        final VdrException exception =
                assertThrows(
                        VdrException.class,
                        () ->
                                revRegEntryService.createRevRegEntry(
                                        ISSUER_DID, REV_REG_DEF_ID, GENESIS_ACCUM));
        assertEquals("ALREADY_EXISTS", exception.getErrorCode());
    }

    /** Verifies updates replace the accumulator value verbatim and increment the version. */
    @Test
    void updateRevRegEntryReplacesValueAndIncrementsVersion() {
        final RevRegEntryRecord existing = new RevRegEntryRecord(REV_REG_DEF_ID, GENESIS_ACCUM, 3L);
        when(revRegEntryRegistry.get(REV_REG_DEF_ID)).thenReturn(existing);
        when(revRegDefRegistry.get(REV_REG_DEF_ID)).thenReturn(parentOwnedBy(ISSUER_DID));

        revRegEntryService.updateRevRegEntry(ISSUER_DID, REV_REG_DEF_ID, NEXT_ACCUM);

        final ArgumentCaptor<RevRegEntryRecord> captor =
                ArgumentCaptor.forClass(RevRegEntryRecord.class);
        verify(revRegEntryRegistry).put(eq(REV_REG_DEF_ID), captor.capture());
        assertEquals(NEXT_ACCUM, captor.getValue().getAccumValue());
        assertEquals(4L, captor.getValue().getVersion());
        assertEquals(TX_TIME.toString(), captor.getValue().getUpdatedAt());
    }

    /** Verifies accumulator updates emit the witness-refresh event with the new value. */
    @Test
    void updateRevRegEntryEmitsUpdatedEventWithNewAccumulator() {
        final RevRegEntryRecord existing = new RevRegEntryRecord(REV_REG_DEF_ID, GENESIS_ACCUM, 0L);
        when(revRegEntryRegistry.get(REV_REG_DEF_ID)).thenReturn(existing);
        when(revRegDefRegistry.get(REV_REG_DEF_ID)).thenReturn(parentOwnedBy(ISSUER_DID));

        revRegEntryService.updateRevRegEntry(ISSUER_DID, REV_REG_DEF_ID, NEXT_ACCUM);

        final ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventEmitter).emit(eq(VdrEvents.REV_REG_ENTRY_UPDATED), payloadCaptor.capture());
        final Map<?, ?> payload = (Map<?, ?>) payloadCaptor.getValue();
        assertEquals(REV_REG_DEF_ID, payload.get("revRegDefId"));
        assertEquals(NEXT_ACCUM, payload.get("accumValue"));
        assertEquals(1L, payload.get("version"));
        assertEquals(3, payload.size());
    }

    /** Verifies updates from a non-owner signer are rejected without any ledger write. */
    @Test
    void updateRevRegEntryRejectsNonOwnerSigner() {
        final RevRegEntryRecord existing = new RevRegEntryRecord(REV_REG_DEF_ID, GENESIS_ACCUM, 0L);
        when(revRegEntryRegistry.get(REV_REG_DEF_ID)).thenReturn(existing);
        when(revRegDefRegistry.get(REV_REG_DEF_ID)).thenReturn(parentOwnedBy(ISSUER_DID));

        final VdrException exception =
                assertThrows(
                        VdrException.class,
                        () ->
                                revRegEntryService.updateRevRegEntry(
                                        OTHER_DID, REV_REG_DEF_ID, HIJACK_ACCUM));
        assertEquals("UNAUTHORIZED", exception.getErrorCode());
        verify(revRegEntryRegistry, never()).put(anyString(), any());
        verify(eventEmitter, never()).emit(anyString(), any());
    }

    /** Verifies history resolution delegates to the registry and preserves revision order. */
    @Test
    void getRevRegEntryHistoryDelegatesToRegistry() {
        final RevRegEntryRecord genesis = new RevRegEntryRecord(REV_REG_DEF_ID, GENESIS_ACCUM, 0L);
        final List<KeyRevision<RevRegEntryRecord>> revisions =
                List.of(new KeyRevision<>("tx-1", TX_TIME, false, genesis));
        when(revRegEntryRegistry.history(REV_REG_DEF_ID)).thenReturn(revisions);

        final List<KeyRevision<RevRegEntryRecord>> result =
                revRegEntryService.getRevRegEntryHistory(REV_REG_DEF_ID);

        assertEquals(1, result.size());
        assertEquals("tx-1", result.get(0).getTxId());
        assertEquals(GENESIS_ACCUM, result.get(0).getValue().getAccumValue());
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
        when(revRegEntryRegistry.get(REV_REG_DEF_ID)).thenReturn(null);

        assertThrows(
                VdrException.class,
                () -> revRegEntryService.updateRevRegEntry(ISSUER_DID, REV_REG_DEF_ID, NEXT_ACCUM));
    }

    /** Verifies malformed accumulator encodings are rejected before any ledger access. */
    @Test
    void createRevRegEntryRejectsMalformedAccumValue() {
        final VdrException notHex =
                assertThrows(
                        VdrException.class,
                        () ->
                                revRegEntryService.createRevRegEntry(
                                        ISSUER_DID, REV_REG_DEF_ID, "accum-genesis"));
        assertEquals("INVALID_ARGUMENT", notHex.getErrorCode());

        final VdrException uppercase =
                assertThrows(
                        VdrException.class,
                        () ->
                                revRegEntryService.updateRevRegEntry(
                                        ISSUER_DID, REV_REG_DEF_ID, GENESIS_ACCUM.toUpperCase()));
        assertEquals("INVALID_ARGUMENT", uppercase.getErrorCode());
    }
}
