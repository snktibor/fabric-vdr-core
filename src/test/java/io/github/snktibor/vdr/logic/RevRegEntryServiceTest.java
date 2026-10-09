package io.github.snktibor.vdr.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.snktibor.vdr.TestVectors;
import io.github.snktibor.vdr.exception.VdrException;
import io.github.snktibor.vdr.ledger.ChaincodeEventEmitter;
import io.github.snktibor.vdr.ledger.KeyRevision;
import io.github.snktibor.vdr.ledger.RevRegDefRegistry;
import io.github.snktibor.vdr.ledger.RevRegEntryRegistry;
import io.github.snktibor.vdr.ledger.VdrEvents;
import io.github.snktibor.vdr.model.RevRegDefRecord;
import io.github.snktibor.vdr.model.RevRegEntryRecord;
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
 * against the parent revocation registry definition's recorded owner — including a parent that
 * records no owner at all — and fail-fast rejection of malformed accumulator values, missing
 * parents, and duplicate entries.
 *
 * <p>The guarded update is covered on three axes: that a passing guard costs the guarded registry
 * nothing (no write, no definition read), that a failing one costs the target everything (no write,
 * no event), and that the checks run in an order that keeps the error semantics clean — the
 * malformed list is refused before any read, and a non-owner is refused before any guard read, so
 * an argument or authorization failure always wins over whatever the guarded registries hold.
 */
@SuppressWarnings("java:S1192")
@ExtendWith(MockitoExtension.class)
class RevRegEntryServiceTest {

    private static final String ISSUER_DID = "did:vdr:018f6c02-3a5e-7cbb-9d3e-5f2a4b6c8d0e";
    private static final String OTHER_DID = "did:vdr:018f6c09-2c55-7d61-b4a8-6f03e19b7c22";
    private static final String REV_REG_DEF_ID =
            ISSUER_DID + "/anoncreds/v2/REV_REG_DEF/018f6c07-9e04-7ff1-a3c6-71b28e05d9a4";
    private static final String GUARD_ONE_ID =
            OTHER_DID + "/anoncreds/v2/REV_REG_DEF/018f6c08-1a72-7c40-9e15-2d84f3b0a6c7";
    private static final String GUARD_TWO_ID =
            OTHER_DID + "/anoncreds/v2/REV_REG_DEF/018f6c0a-53b9-7e88-8f21-c06d95e4718b";
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
                        new AuthorizationService(),
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

    /**
     * Verifies a parent definition carrying no recorded owner is rejected as unauthorized. The
     * shared owner check reads the missing owner as absent authority; the inline comparison this
     * replaced dereferenced it and would have failed the transaction with a null pointer instead.
     */
    @Test
    void createRevRegEntryRejectsParentWithoutOwner() {
        when(revRegDefRegistry.get(REV_REG_DEF_ID)).thenReturn(parentOwnedBy(null));

        final VdrException exception =
                assertThrows(
                        VdrException.class,
                        () ->
                                revRegEntryService.createRevRegEntry(
                                        ISSUER_DID, REV_REG_DEF_ID, GENESIS_ACCUM));
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

    /** Verifies a parent definition carrying no recorded owner is rejected on update too. */
    @Test
    void updateRevRegEntryRejectsParentWithoutOwner() {
        final RevRegEntryRecord existing = new RevRegEntryRecord(REV_REG_DEF_ID, GENESIS_ACCUM, 0L);
        when(revRegEntryRegistry.get(REV_REG_DEF_ID)).thenReturn(existing);
        when(revRegDefRegistry.get(REV_REG_DEF_ID)).thenReturn(parentOwnedBy(null));

        final VdrException exception =
                assertThrows(
                        VdrException.class,
                        () ->
                                revRegEntryService.updateRevRegEntry(
                                        ISSUER_DID, REV_REG_DEF_ID, NEXT_ACCUM));
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

    /** Renders one guard element. */
    private static String guard(final String revRegDefId, final long expectedVersion) {
        return "{\"revRegDefId\":\""
                + revRegDefId
                + "\",\"expectedVersion\":"
                + expectedVersion
                + "}";
    }

    /** Joins pre-rendered guard elements into a guard array payload. */
    private static String guards(final String... elements) {
        return "[" + String.join(",", elements) + "]";
    }

    /** Builds a distinct guarded registry identifier for bulk fixtures. */
    private static String guardId(final int index) {
        return OTHER_DID
                + "/anoncreds/v2/REV_REG_DEF/018f6c0c-0000-7000-8000-%012d".formatted(index);
    }

    /** Makes the target entry resolvable at the given version and owned by the issuer. */
    private RevRegEntryRecord stubOwnedTarget(final long version) {
        final RevRegEntryRecord existing =
                new RevRegEntryRecord(REV_REG_DEF_ID, GENESIS_ACCUM, version);
        when(revRegEntryRegistry.get(REV_REG_DEF_ID)).thenReturn(existing);
        when(revRegDefRegistry.get(REV_REG_DEF_ID)).thenReturn(parentOwnedBy(ISSUER_DID));
        return existing;
    }

    /** Makes a guarded entry resolvable at the given version. */
    private void stubGuardedEntry(final String revRegDefId, final long version) {
        when(revRegEntryRegistry.get(revRegDefId))
                .thenReturn(new RevRegEntryRecord(revRegDefId, GENESIS_ACCUM, version));
    }

    /** Runs a guarded update against the shared target with the given guard payload. */
    private GuardedRevRegEntryUpdate guardedUpdate(final String guardsJson) {
        return revRegEntryService.updateRevRegEntryIfGuardsUnchanged(
                ISSUER_DID, REV_REG_DEF_ID, NEXT_ACCUM, guardsJson);
    }

    /** Asserts a guard payload is refused as a malformed argument, leaving the ledger untouched. */
    private void assertGuardsRejected(final String guardsJson) {
        final VdrException exception =
                assertThrows(VdrException.class, () -> guardedUpdate(guardsJson));
        assertEquals("INVALID_ARGUMENT", exception.getErrorCode(), guardsJson);
        verify(revRegEntryRegistry, never()).put(anyString(), any());
        verify(eventEmitter, never()).emit(anyString(), any());
    }

    /** Verifies a matching external guard lets the update through with one write and one event. */
    @Test
    void updateRevRegEntryIfGuardsUnchangedCommitsWhenExternalGuardMatches() {
        stubOwnedTarget(0L);
        stubGuardedEntry(GUARD_ONE_ID, 0L);

        final GuardedRevRegEntryUpdate result = guardedUpdate(guards(guard(GUARD_ONE_ID, 0L)));

        final ArgumentCaptor<RevRegEntryRecord> captor =
                ArgumentCaptor.forClass(RevRegEntryRecord.class);
        verify(revRegEntryRegistry, times(1)).put(eq(REV_REG_DEF_ID), captor.capture());
        verify(revRegEntryRegistry, times(1)).put(anyString(), any());
        assertEquals(NEXT_ACCUM, captor.getValue().getAccumValue());
        assertEquals(1L, captor.getValue().getVersion());
        assertEquals(1L, result.getEntry().getVersion());

        final ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventEmitter, times(1))
                .emit(eq(VdrEvents.REV_REG_ENTRY_UPDATED), payloadCaptor.capture());
        verify(eventEmitter, times(1)).emit(anyString(), any());
        final Map<?, ?> payload = (Map<?, ?>) payloadCaptor.getValue();
        assertEquals(3, payload.size());
        assertEquals(REV_REG_DEF_ID, payload.get("revRegDefId"));
        assertEquals(NEXT_ACCUM, payload.get("accumValue"));
        assertEquals(1L, payload.get("version"));

        assertEquals(1, result.getCheckedGuards().size());
        assertEquals(GUARD_ONE_ID, result.getCheckedGuards().get(0).getRevRegDefId());
        assertEquals(0L, result.getCheckedGuards().get(0).getCheckedVersion());
    }

    /**
     * Verifies the guarded entry is read and that its reported version is neither of the target's.
     *
     * <p>What this pins: the guarded record is interrogated rather than assumed, and the version
     * reported for the guard is distinct from the target's version both before (2) and after (3)
     * the mutation, so a value taken from the wrong record fails here instead of silently agreeing.
     * {@code …CommitsWhenExternalGuardMatches} cannot catch that, since there every number is 0.
     *
     * <p>What this does NOT pin, and no service-level test can: that the reported version came from
     * the read rather than from the caller's submitted one. A guard only reaches the response when
     * those two numbers are equal — that equality is the test — so at this layer they are
     * indistinguishable, and {@code verify(guarded).getVersion()} passes either way because the
     * comparison interrogates the record regardless. Measured, not assumed: an implementation that
     * compares against the read version but reports the submitted one passes this whole suite. What
     * actually keeps the submitted value out of the response is {@link CheckedRevRegEntryGuard}
     * carrying no expected version at all, so the contract layer has nothing else to write; {@code
     * VdrContractTest} asserts that propagation with numbers driven deliberately apart.
     */
    @Test
    void updateRevRegEntryIfGuardsUnchangedReadsGuardedEntryAndReportsItsOwnVersion() {
        stubOwnedTarget(2L);
        final RevRegEntryRecord guarded = mock(RevRegEntryRecord.class);
        when(guarded.getVersion()).thenReturn(6L);
        when(revRegEntryRegistry.get(GUARD_ONE_ID)).thenReturn(guarded);

        final GuardedRevRegEntryUpdate result = guardedUpdate(guards(guard(GUARD_ONE_ID, 6L)));

        verify(guarded).getVersion();
        assertEquals(GUARD_ONE_ID, result.getCheckedGuards().get(0).getRevRegDefId());
        assertEquals(6L, result.getCheckedGuards().get(0).getCheckedVersion());
        // The target moved 2 -> 3 in the same transaction; neither number is the guard's.
        assertEquals(3L, result.getEntry().getVersion());
    }

    /** Verifies passing guards are read only: no guard registry is written or resolved further. */
    @Test
    void updateRevRegEntryIfGuardsUnchangedNeverWritesGuardedRegistries() {
        stubOwnedTarget(2L);
        stubGuardedEntry(GUARD_ONE_ID, 1L);
        stubGuardedEntry(GUARD_TWO_ID, 5L);

        final GuardedRevRegEntryUpdate result =
                guardedUpdate(
                        guards(
                                guard(GUARD_ONE_ID, 1L),
                                guard(GUARD_TWO_ID, 5L),
                                guard(REV_REG_DEF_ID, 2L)));

        assertEquals(3, result.getCheckedGuards().size());
        verify(revRegEntryRegistry, times(1)).put(anyString(), any());
        verify(revRegEntryRegistry, never()).put(eq(GUARD_ONE_ID), any());
        verify(revRegEntryRegistry, never()).put(eq(GUARD_TWO_ID), any());
        // Invariant: a guard's parent definition is never resolved — one read-set key per guard.
        verify(revRegDefRegistry, never()).get(GUARD_ONE_ID);
        verify(revRegDefRegistry, never()).get(GUARD_TWO_ID);
    }

    /** Verifies a self-guard is decided against the version the entry held before the mutation. */
    @Test
    void updateRevRegEntryIfGuardsUnchangedChecksSelfGuardAgainstPreMutationVersion() {
        stubOwnedTarget(3L);

        final GuardedRevRegEntryUpdate result = guardedUpdate(guards(guard(REV_REG_DEF_ID, 3L)));

        assertEquals(4L, result.getEntry().getVersion());
        assertEquals(3L, result.getCheckedGuards().get(0).getCheckedVersion());
        // The self-guard reuses the record already loaded; it must not cost a second read.
        verify(revRegEntryRegistry, times(1)).get(REV_REG_DEF_ID);
    }

    /** Verifies a moved external guard rejects the update without writing or announcing it. */
    @Test
    void updateRevRegEntryIfGuardsUnchangedRejectsStaleExternalGuard() {
        stubOwnedTarget(0L);
        stubGuardedEntry(GUARD_ONE_ID, 2L);

        final VdrException exception =
                assertThrows(
                        VdrException.class, () -> guardedUpdate(guards(guard(GUARD_ONE_ID, 1L))));

        assertEquals("FAILED_PRECONDITION", exception.getErrorCode());
        verify(revRegEntryRegistry, never()).put(anyString(), any());
        verify(eventEmitter, never()).emit(anyString(), any());
    }

    /** Verifies the last guard in the list is checked too, so no element is skipped. */
    @Test
    void updateRevRegEntryIfGuardsUnchangedRejectsStaleLastGuard() {
        stubOwnedTarget(0L);
        stubGuardedEntry(GUARD_ONE_ID, 0L);
        stubGuardedEntry(GUARD_TWO_ID, 3L);

        final VdrException exception =
                assertThrows(
                        VdrException.class,
                        () ->
                                guardedUpdate(
                                        guards(guard(GUARD_ONE_ID, 0L), guard(GUARD_TWO_ID, 0L))));

        assertEquals("FAILED_PRECONDITION", exception.getErrorCode());
        assertTrue(exception.getMessage().contains(GUARD_TWO_ID), exception.getMessage());
        verify(revRegEntryRegistry, never()).put(anyString(), any());
        verify(eventEmitter, never()).emit(anyString(), any());
    }

    /** Verifies a self-guard pinned to the post-mutation version closes the last-write-wins gap. */
    @Test
    void updateRevRegEntryIfGuardsUnchangedRejectsStaleSelfGuard() {
        stubOwnedTarget(3L);

        final VdrException exception =
                assertThrows(
                        VdrException.class, () -> guardedUpdate(guards(guard(REV_REG_DEF_ID, 4L))));

        assertEquals("FAILED_PRECONDITION", exception.getErrorCode());
        verify(revRegEntryRegistry, never()).put(anyString(), any());
        verify(eventEmitter, never()).emit(anyString(), any());
    }

    /** Verifies an absent guarded entry is not silently a version-zero entry. */
    @Test
    void updateRevRegEntryIfGuardsUnchangedRejectsMissingGuardPinnedToVersionZero() {
        stubOwnedTarget(0L);
        when(revRegEntryRegistry.get(GUARD_ONE_ID)).thenReturn(null);

        final VdrException exception =
                assertThrows(
                        VdrException.class, () -> guardedUpdate(guards(guard(GUARD_ONE_ID, 0L))));

        assertEquals("NOT_FOUND", exception.getErrorCode());
        verify(revRegEntryRegistry, never()).put(anyString(), any());
        verify(eventEmitter, never()).emit(anyString(), any());
    }

    /** Verifies an absent guarded entry reports absence, not a version mismatch. */
    @Test
    void updateRevRegEntryIfGuardsUnchangedRejectsMissingGuardPinnedToNonZeroVersion() {
        stubOwnedTarget(0L);
        when(revRegEntryRegistry.get(GUARD_ONE_ID)).thenReturn(null);

        final VdrException exception =
                assertThrows(
                        VdrException.class, () -> guardedUpdate(guards(guard(GUARD_ONE_ID, 4L))));

        assertEquals("NOT_FOUND", exception.getErrorCode());
    }

    /** Verifies the first failure in submission order decides, so every endorser agrees. */
    @Test
    void updateRevRegEntryIfGuardsUnchangedReportsFirstFailureInListOrder() {
        stubOwnedTarget(0L);
        when(revRegEntryRegistry.get(GUARD_ONE_ID)).thenReturn(null);
        // The second guard is stale as well, but the loop must never reach it.
        lenient()
                .when(revRegEntryRegistry.get(GUARD_TWO_ID))
                .thenReturn(new RevRegEntryRecord(GUARD_TWO_ID, GENESIS_ACCUM, 9L));

        final VdrException exception =
                assertThrows(
                        VdrException.class,
                        () ->
                                guardedUpdate(
                                        guards(guard(GUARD_ONE_ID, 0L), guard(GUARD_TWO_ID, 0L))));

        assertEquals("NOT_FOUND", exception.getErrorCode());
        verify(revRegEntryRegistry, never()).get(GUARD_TWO_ID);
    }

    /** Verifies the owner check precedes every guard read, so the authorization failure wins. */
    @Test
    void updateRevRegEntryIfGuardsUnchangedRejectsNonOwnerBeforeReadingGuards() {
        stubOwnedTarget(0L);

        final VdrException exception =
                assertThrows(
                        VdrException.class,
                        () ->
                                revRegEntryService.updateRevRegEntryIfGuardsUnchanged(
                                        OTHER_DID,
                                        REV_REG_DEF_ID,
                                        HIJACK_ACCUM,
                                        guards(guard(GUARD_ONE_ID, 0L))));

        assertEquals("UNAUTHORIZED", exception.getErrorCode());
        verify(revRegEntryRegistry, never()).get(GUARD_ONE_ID);
        verify(revRegEntryRegistry, never()).put(anyString(), any());
        verify(eventEmitter, never()).emit(anyString(), any());
    }

    /** Verifies a malformed guard list is settled before the transaction reads any state. */
    @Test
    void updateRevRegEntryIfGuardsUnchangedRejectsMalformedListBeforeAnyRead() {
        final VdrException exception =
                assertThrows(VdrException.class, () -> guardedUpdate("not json at all"));

        assertEquals("INVALID_ARGUMENT", exception.getErrorCode());
        verify(revRegEntryRegistry, never()).get(anyString());
        verify(revRegDefRegistry, never()).get(anyString());
        verify(revRegEntryRegistry, never()).put(anyString(), any());
        verify(eventEmitter, never()).emit(anyString(), any());
    }

    /** Verifies payloads that are not a JSON array are refused, the null literal included. */
    @Test
    void updateRevRegEntryIfGuardsUnchangedRejectsNonArrayPayloads() {
        assertGuardsRejected("not json at all");
        assertGuardsRejected("null");
        assertGuardsRejected(guard(GUARD_ONE_ID, 0L));
        assertGuardsRejected("\"" + GUARD_ONE_ID + "\"");
        assertGuardsRejected("7");
    }

    /** Verifies both ends of the list-size bound: an empty list and one element over the cap. */
    @Test
    void updateRevRegEntryIfGuardsUnchangedRejectsListSizeOutOfBounds() {
        assertGuardsRejected("[]");

        final String[] tooMany = new String[ValidationService.MAX_GUARDS + 1];
        for (int index = 0; index < tooMany.length; index++) {
            tooMany[index] = guard(guardId(index), index);
        }
        assertGuardsRejected(guards(tooMany));
    }

    /** Verifies the cap is inclusive: exactly MAX_GUARDS guards still commit. */
    @Test
    void updateRevRegEntryIfGuardsUnchangedAcceptsExactlyMaxGuards() {
        stubOwnedTarget(0L);
        final String[] elements = new String[ValidationService.MAX_GUARDS];
        for (int index = 0; index < elements.length; index++) {
            stubGuardedEntry(guardId(index), index);
            elements[index] = guard(guardId(index), index);
        }

        final GuardedRevRegEntryUpdate result = guardedUpdate(guards(elements));

        assertEquals(ValidationService.MAX_GUARDS, result.getCheckedGuards().size());
        assertEquals(1L, result.getEntry().getVersion());
        verify(revRegEntryRegistry, times(1)).put(anyString(), any());
    }

    /** Verifies each element must be an object carrying exactly the two guard fields. */
    @Test
    void updateRevRegEntryIfGuardsUnchangedRejectsMalformedGuardElements() {
        assertGuardsRejected("[\"" + GUARD_ONE_ID + "\"]");
        assertGuardsRejected("[[]]");
        assertGuardsRejected("[null]");
        assertGuardsRejected("[{\"revRegDefId\":\"" + GUARD_ONE_ID + "\"}]");
        assertGuardsRejected("[{\"expectedVersion\":0}]");
        assertGuardsRejected(
                "[{\"revRegDefId\":\""
                        + GUARD_ONE_ID
                        + "\",\"expectedVersion\":0,\"note\":\"x\"}]");
        assertGuardsRejected("[{\"revRegDefId\":\"\",\"expectedVersion\":0}]");
        assertGuardsRejected("[{\"revRegDefId\":\"   \",\"expectedVersion\":0}]");
        assertGuardsRejected("[{\"revRegDefId\":7,\"expectedVersion\":0}]");
    }

    /** Verifies the expected version must be an integer inside the unsigned long-range window. */
    @Test
    void updateRevRegEntryIfGuardsUnchangedRejectsMalformedExpectedVersions() {
        assertGuardsRejected(
                "[{\"revRegDefId\":\"" + GUARD_ONE_ID + "\",\"expectedVersion\":\"3\"}]");
        assertGuardsRejected(
                "[{\"revRegDefId\":\"" + GUARD_ONE_ID + "\",\"expectedVersion\":3.0}]");
        assertGuardsRejected(guards(guard(GUARD_ONE_ID, -1L)));
        assertGuardsRejected(
                "[{\"revRegDefId\":\""
                        + GUARD_ONE_ID
                        + "\",\"expectedVersion\":9223372036854775808}]");
        assertGuardsRejected(
                "[{\"revRegDefId\":\"" + GUARD_ONE_ID + "\",\"expectedVersion\":null}]");
    }

    /** Verifies a registry may not be guarded twice, whitespace-only differences included. */
    @Test
    void updateRevRegEntryIfGuardsUnchangedRejectsDuplicateGuardedRegistry() {
        assertGuardsRejected(guards(guard(GUARD_ONE_ID, 0L), guard(GUARD_ONE_ID, 0L)));
        assertGuardsRejected(guards(guard(GUARD_ONE_ID, 0L), guard(GUARD_ONE_ID, 4L)));
        assertGuardsRejected(guards(guard(GUARD_ONE_ID, 0L), guard("  " + GUARD_ONE_ID + " ", 4L)));
    }

    /**
     * Verifies a repeated key inside a guard element is a parse error rather than a silent
     * last-wins resolution, so the signed text has exactly one reading.
     */
    @Test
    void updateRevRegEntryIfGuardsUnchangedRejectsDuplicateJsonKeys() {
        assertGuardsRejected(
                "[{\"revRegDefId\":\""
                        + GUARD_ONE_ID
                        + "\",\"revRegDefId\":\""
                        + GUARD_TWO_ID
                        + "\",\"expectedVersion\":0}]");
        assertGuardsRejected(
                "[{\"revRegDefId\":\""
                        + GUARD_ONE_ID
                        + "\",\"expectedVersion\":0,\"expectedVersion\":4}]");
        // The decoded key name decides, so an escaped spelling is the same key.
        assertGuardsRejected(
                "[{\"revRegDefId\":\""
                        + GUARD_ONE_ID
                        + "\",\"\\u0072evRegDefId\":\""
                        + GUARD_TWO_ID
                        + "\",\"expectedVersion\":0}]");

        // Neither reading of the repeated key reached the ledger.
        verify(revRegEntryRegistry, never()).get(GUARD_ONE_ID);
        verify(revRegEntryRegistry, never()).get(GUARD_TWO_ID);
        verify(revRegEntryRegistry, never()).get(anyString());
    }

    /** Verifies content after the guard array is a parse error, not ignored trailing text. */
    @Test
    void updateRevRegEntryIfGuardsUnchangedRejectsContentTrailingTheArray() {
        assertGuardsRejected(
                guards(guard(GUARD_ONE_ID, 0L)) + " " + guards(guard(GUARD_TWO_ID, 0L)));
        assertGuardsRejected(guards(guard(GUARD_ONE_ID, 0L)) + " garbage");
        assertGuardsRejected(guards(guard(GUARD_ONE_ID, 0L)) + " 7");
        verify(revRegEntryRegistry, never()).get(anyString());
    }

    /** Verifies the rejection message names the registry and both versions, and claims no more. */
    @Test
    void updateRevRegEntryIfGuardsUnchangedReportsVersionsWithoutClaimingRevocation() {
        stubOwnedTarget(0L);
        stubGuardedEntry(GUARD_ONE_ID, 5L);

        final VdrException exception =
                assertThrows(
                        VdrException.class, () -> guardedUpdate(guards(guard(GUARD_ONE_ID, 2L))));

        assertEquals(
                "guard registry '"
                        + GUARD_ONE_ID
                        + "' is at version 5, expected 2: it has changed since the state this"
                        + " operation was tested against",
                exception.getMessage());
        assertTrue(exception.getMessage().contains(GUARD_ONE_ID));
        // The chain observes a version move, never a revocation; the message must not claim one.
        assertFalse(exception.getMessage().toLowerCase().contains("revoked"));
    }
}
