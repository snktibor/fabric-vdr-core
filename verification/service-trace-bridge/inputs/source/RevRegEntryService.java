package com.example.vdr.logic;

import com.example.vdr.exception.VdrException;
import com.example.vdr.ledger.ChaincodeEventEmitter;
import com.example.vdr.ledger.KeyRevision;
import com.example.vdr.ledger.RevRegDefRegistry;
import com.example.vdr.ledger.RevRegEntryRegistry;
import com.example.vdr.ledger.VdrEvents;
import com.example.vdr.model.RevRegDefRecord;
import com.example.vdr.model.RevRegEntryRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Layer-3 business service for revocation registry entry lifecycle operations.
 *
 * <p>This pure Java service orchestrates validation, owner-only authorization, and referential
 * integrity checks while delegating all ledger I/O to injected registries from the data-access
 * layer. The entry stores the off-chain-computed VB20 accumulator value (Vitto–Biryukov, eprint
 * 2020/777; a compressed BLS12-381 G1 point in canonical hex form) verbatim after format
 * validation: no accumulator cryptography is performed on chain. The entry is the only mutable VDR
 * record; each update replaces the accumulator value in place and increments the audit {@code
 * version}. Fabric MVCC validation serializes concurrent submits to <em>one</em> key, so on that
 * key the version is auditable metadata rather than a concurrency guard. Across keys it serializes
 * nothing, because an entry a transaction never read is not in its read set; {@link
 * #updateRevRegEntryIfGuardsUnchanged} is what extends the guarantee to other entries, by reading
 * them inside the transaction.
 */
public class RevRegEntryService {

    private final RevRegEntryRegistry revRegEntryRegistry;
    private final RevRegDefRegistry revRegDefRegistry;
    private final AuthorizationService authorizationService;
    private final ValidationService validationService;
    private final ChaincodeEventEmitter eventEmitter;
    private final Instant txTimestamp;

    /**
     * Creates a revocation registry entry service with its required dependencies.
     *
     * @param revRegEntryRegistry registry used to persist revocation registry entries
     * @param revRegDefRegistry registry used to resolve parent revocation registry definitions
     * @param authorizationService owner authorization utility
     * @param validationService fail-fast input and JSON validation utility
     * @param eventEmitter chaincode event emitter for state-change notifications
     * @param txTimestamp client-declared transaction time used for audit timestamps
     */
    public RevRegEntryService(
            final RevRegEntryRegistry revRegEntryRegistry,
            final RevRegDefRegistry revRegDefRegistry,
            final AuthorizationService authorizationService,
            final ValidationService validationService,
            final ChaincodeEventEmitter eventEmitter,
            final Instant txTimestamp) {
        this.revRegEntryRegistry = revRegEntryRegistry;
        this.revRegDefRegistry = revRegDefRegistry;
        this.authorizationService = authorizationService;
        this.validationService = validationService;
        this.eventEmitter = eventEmitter;
        this.txTimestamp = txTimestamp;
    }

    /**
     * Creates and stores the genesis revocation registry entry for a definition.
     *
     * <p>The method enforces deterministic preconditions before persistence: required input fields,
     * parent revocation registry definition existence, owner-only authorization against the
     * parent's recorded owner, and one-entry-per-definition uniqueness. The {@code signerDid}
     * argument is resolved from the verified DID-JWT upstream by the contract layer; no token
     * verification happens here.
     *
     * @param signerDid DID resolved from verified JWT issuer claim
     * @param revRegDefId parent revocation registry definition identifier
     * @param accumValue genesis accumulator value computed off chain (compressed BLS12-381 G1 point
     *     as 96 lowercase hex characters)
     * @return created and persisted revocation registry entry record with version 0
     * @throws VdrException if validation fails, the parent definition is missing or records no
     *     owner, the signer does not own the parent definition, or an entry already exists for the
     *     definition
     */
    public RevRegEntryRecord createRevRegEntry(
            final String signerDid, final String revRegDefId, final String accumValue) {
        final String normalizedRevRegDefId =
                validationService.requireNotBlank(revRegDefId, "revRegDefId");
        final String normalizedAccumValue =
                validationService.requireCompressedG1Hex(accumValue, "accumValue");

        final RevRegDefRecord parent = revRegDefRegistry.get(normalizedRevRegDefId);
        if (parent == null) {
            throw VdrException.notFound("revregdef", normalizedRevRegDefId);
        }
        authorizationService.assertSignerOwnsEntity(
                signerDid, parent.getOwnerId(), "revregdef", normalizedRevRegDefId);

        if (revRegEntryRegistry.exists(normalizedRevRegDefId)) {
            throw VdrException.duplicate("revregentry", normalizedRevRegDefId);
        }

        final RevRegEntryRecord revRegEntryRecord =
                new RevRegEntryRecord(normalizedRevRegDefId, normalizedAccumValue, 0L);
        revRegEntryRecord.setCreatedAt(txTimeIso());
        revRegEntryRecord.setUpdatedAt(txTimeIso());

        revRegEntryRegistry.put(normalizedRevRegDefId, revRegEntryRecord);
        eventEmitter.emit(VdrEvents.REV_REG_ENTRY_CREATED, entryEventPayload(revRegEntryRecord));
        return revRegEntryRecord;
    }

    /**
     * Replaces the accumulator value of an existing revocation registry entry in place.
     *
     * <p>Only the owner of the parent revocation registry definition may update the entry. The
     * submitted value is stored verbatim and the audit {@code version} is incremented by one.
     *
     * @param signerDid DID resolved from verified JWT issuer claim
     * @param revRegDefId parent revocation registry definition identifier
     * @param newAccumValue replacement accumulator value computed off chain (compressed BLS12-381
     *     G1 point as 96 lowercase hex characters)
     * @return updated and persisted revocation registry entry record
     * @throws VdrException if validation fails, the entry or its parent definition is missing, the
     *     parent definition records no owner, the signer does not own the parent definition, or the
     *     stored version is negative or exhausted ({@code FAILED_PRECONDITION})
     */
    public RevRegEntryRecord updateRevRegEntry(
            final String signerDid, final String revRegDefId, final String newAccumValue) {
        final String normalizedRevRegDefId =
                validationService.requireNotBlank(revRegDefId, "revRegDefId");
        final String normalizedAccumValue =
                validationService.requireCompressedG1Hex(newAccumValue, "accumValue");

        final RevRegEntryRecord existing = requireOwnedEntry(signerDid, normalizedRevRegDefId);
        return applyAccumulatorUpdate(existing, normalizedRevRegDefId, normalizedAccumValue);
    }

    /**
     * Replaces the accumulator value of an entry only while every named revocation registry entry
     * still stands at the version the caller observed.
     *
     * <p>The guarantee has two halves, and neither holds without the other. Between the caller's
     * off-chain observation and endorsement, what closes the window is the comparison: the version
     * submitted for each guard is checked against the version read here, inside the transaction.
     * Between endorsement and commit, what closes the window is the read itself: reading a guarded
     * entry puts its key into this transaction's read set, so Fabric MVCC validation re-checks the
     * key at commit time and rejects the transaction if anything wrote to it in between. This is
     * why a guard may only name a registry and a version — state handed in as an argument would be
     * compared against nothing and would enter no read set.
     *
     * <p>The guarantee is opt-in and bounded by this transaction's own commit. It says the guarded
     * entries did not move between the observed state and this commit; it says nothing about what
     * happens to them afterwards, and nothing about writers taking the unguarded {@link
     * #updateRevRegEntry} path.
     *
     * <p>The updated entry may guard itself, which makes the update a compare-and-swap on its own
     * version: the value is written only if the entry is still the one the new accumulator was
     * computed from. Its version is then read from the entry already loaded above, before the
     * mutation, and no second read is issued.
     *
     * <p>No guarded registry is ever written to, and no guard's parent definition is read: a write
     * would entangle the write sets of independent revocations, and each extra key read is one more
     * key that can fail validation. A successful update emits the same single {@code
     * RevRegEntryUpdated} event as the unguarded path — evidence that the guards held is returned
     * to the caller, not broadcast to witness-refresh subscribers.
     *
     * @param signerDid DID resolved from verified JWT issuer claim
     * @param revRegDefId parent revocation registry definition identifier of the entry to update
     * @param newAccumValue replacement accumulator value computed off chain (compressed BLS12-381
     *     G1 point as 96 lowercase hex characters)
     * @param guardsJson JSON array of guards, each naming a revocation registry entry and the
     *     version it must still be at; see {@link ValidationService#parseRevRegEntryGuards}
     * @return the updated entry together with the guards it was conditioned on
     * @throws VdrException {@code INVALID_ARGUMENT} if any argument or the guard list is malformed;
     *     {@code NOT_FOUND} if the entry, its parent definition, or any guarded entry is missing;
     *     {@code UNAUTHORIZED} if the signer does not own the parent definition; {@code
     *     FAILED_PRECONDITION} if a guarded entry stands at a different version or the target's
     *     stored version is negative or exhausted
     */
    public GuardedRevRegEntryUpdate updateRevRegEntryIfGuardsUnchanged(
            final String signerDid,
            final String revRegDefId,
            final String newAccumValue,
            final String guardsJson) {
        final String normalizedRevRegDefId =
                validationService.requireNotBlank(revRegDefId, "revRegDefId");
        final String normalizedAccumValue =
                validationService.requireCompressedG1Hex(newAccumValue, "accumValue");
        final List<RevRegEntryGuard> guards =
                validationService.parseRevRegEntryGuards(guardsJson, "guards");

        // The owner check runs before any guard is read, so an authorization failure always wins:
        // a caller who does not own the target gets UNAUTHORIZED whatever it named as a guard.
        final RevRegEntryRecord existing = requireOwnedEntry(signerDid, normalizedRevRegDefId);

        final List<CheckedRevRegEntryGuard> checkedGuards = new ArrayList<>();
        for (final RevRegEntryGuard guard : guards) {
            checkedGuards.add(checkGuardUnchanged(guard, normalizedRevRegDefId, existing));
        }

        applyAccumulatorUpdate(existing, normalizedRevRegDefId, normalizedAccumValue);
        return new GuardedRevRegEntryUpdate(existing, checkedGuards);
    }

    /**
     * Reads one guarded entry inside the transaction and requires it to stand at the pinned
     * version.
     *
     * <p>The version read here is the single source for all three uses that follow: the comparison
     * that decides the guard, the message if it fails, and the value reported back if it passes.
     * The submitted expected version is used for the comparison and nowhere else — a caller's claim
     * about world state is not evidence about world state, so it never reaches the response.
     *
     * <p>A guard naming the entry being updated is answered from the record already loaded, whose
     * key is in the read set since the target was resolved, and whose version is still the one it
     * had before this transaction's mutation. Every other guard is resolved through its own read,
     * which is what admits its key to the read set.
     *
     * <p>A guarded entry that does not exist fails the transaction. An absent record is not a
     * version-zero record: whether the identifier names nothing at all or names a definition whose
     * genesis entry was never created is indistinguishable from here, and neither is a state a
     * caller could have observed and pinned. Treating absence as a match would let a guard naming a
     * typo pass unconditionally.
     *
     * @param guard guard to check
     * @param targetRevRegDefId identifier of the entry this transaction updates
     * @param target entry this transaction updates, in its pre-mutation state
     * @return the guard together with the version read for it
     * @throws VdrException {@code NOT_FOUND} if the guarded entry does not exist, or {@code
     *     FAILED_PRECONDITION} if it stands at a different version
     */
    private CheckedRevRegEntryGuard checkGuardUnchanged(
            final RevRegEntryGuard guard,
            final String targetRevRegDefId,
            final RevRegEntryRecord target) {
        final CheckedRevRegEntryGuard checked =
                new CheckedRevRegEntryGuard(
                        guard.getRevRegDefId(),
                        readGuardedVersion(guard, targetRevRegDefId, target));

        if (checked.getCheckedVersion() != guard.getExpectedVersion()) {
            // The chaincode knows only that the version moved, never why; the message must not
            // suggest a revocation took place.
            throw VdrException.failedPrecondition(
                    "guard registry '"
                            + guard.getRevRegDefId()
                            + "' is at version "
                            + checked.getCheckedVersion()
                            + ", expected "
                            + guard.getExpectedVersion()
                            + ": it has changed since the state this operation was tested against");
        }
        return checked;
    }

    /**
     * Resolves the current version of one guarded entry from world state.
     *
     * @param guard guard naming the entry to read
     * @param targetRevRegDefId identifier of the entry this transaction updates
     * @param target entry this transaction updates, in its pre-mutation state
     * @return version the guarded entry stands at inside this transaction
     * @throws VdrException {@code NOT_FOUND} if the guarded entry does not exist
     */
    private long readGuardedVersion(
            final RevRegEntryGuard guard,
            final String targetRevRegDefId,
            final RevRegEntryRecord target) {
        if (guard.getRevRegDefId().equals(targetRevRegDefId)) {
            return target.getVersion();
        }
        final RevRegEntryRecord guarded = revRegEntryRegistry.get(guard.getRevRegDefId());
        if (guarded == null) {
            throw VdrException.notFound("revregentry", guard.getRevRegDefId());
        }
        return guarded.getVersion();
    }

    /**
     * Resolves an existing entry and its parent definition, and requires the signer to own the
     * parent.
     *
     * <p>The entry carries no owner of its own, so authority is always read from the parent
     * definition; the existence of the entry is established first, as it has been on this path
     * since before the guarded variant existed.
     *
     * @param signerDid DID resolved from verified JWT issuer claim
     * @param revRegDefId normalized parent revocation registry definition identifier
     * @return the existing entry record, unmodified
     * @throws VdrException if the entry or its parent definition is missing, the parent records no
     *     owner, or the signer does not own it
     */
    private RevRegEntryRecord requireOwnedEntry(final String signerDid, final String revRegDefId) {
        final RevRegEntryRecord existing = revRegEntryRegistry.get(revRegDefId);
        if (existing == null) {
            throw VdrException.notFound("revregentry", revRegDefId);
        }

        final RevRegDefRecord parent = revRegDefRegistry.get(revRegDefId);
        if (parent == null) {
            throw VdrException.notFound("revregdef", revRegDefId);
        }
        authorizationService.assertSignerOwnsEntity(
                signerDid, parent.getOwnerId(), "revregdef", revRegDefId);
        return existing;
    }

    /**
     * Applies the accumulator replacement in place, persists it, and announces it.
     *
     * <p>Shared by both update paths so the mutation, the write and the single {@code
     * RevRegEntryUpdated} event stay identical whether or not guards were checked first.
     *
     * @param existing entry record resolved from the ledger
     * @param revRegDefId normalized storage key of the entry
     * @param accumValue validated replacement accumulator value
     * @return the mutated and persisted entry record
     */
    /*@ private exceptional_behavior
    @ requires existing != null && \typeof(existing) == \type(RevRegEntryRecord);
    @ requires existing.version < 0 || existing.version == Long.MAX_VALUE;
    @ signals_only VdrException;
    @ signals (VdrException e) e.errorCode == "FAILED_PRECONDITION";
    @ assignable \nothing;
    @*/
    private RevRegEntryRecord applyAccumulatorUpdate(
            final RevRegEntryRecord existing, final String revRegDefId, final String accumValue) {
        final long newVersion = RevRegEntryVersion.next(existing.getVersion());
        existing.setAccumValue(accumValue);
        existing.setVersion(newVersion);
        existing.setUpdatedAt(txTimeIso());
        revRegEntryRegistry.put(revRegDefId, existing);
        eventEmitter.emit(VdrEvents.REV_REG_ENTRY_UPDATED, entryEventPayload(existing));
        return existing;
    }

    /**
     * Returns the full accumulator revision history of a revocation registry entry, oldest first.
     *
     * <p>Backed by the peer's history index; each revision carries the writing transaction id, the
     * client-declared transaction timestamp, and the entry state as of that write. An empty list
     * means no entry was ever created for the definition. Deletion tombstones (none are produced
     * today) surface as revisions flagged deleted with no record value.
     *
     * @param revRegDefId parent revocation registry definition identifier
     * @return chronological revision list, possibly empty
     * @throws VdrException if the identifier is blank
     */
    public List<KeyRevision<RevRegEntryRecord>> getRevRegEntryHistory(final String revRegDefId) {
        final String normalizedRevRegDefId =
                validationService.requireNotBlank(revRegDefId, "revRegDefId");
        return revRegEntryRegistry.history(normalizedRevRegDefId);
    }

    /**
     * Builds the accumulator event payload: everything a VB20 witness-refresh client needs in one
     * delivery, without a follow-up query.
     *
     * <p>No timestamp is carried. The only time value available on chain is the submitter's
     * unattested channel-header time, which a subscriber receiving it "from the chain" would
     * reasonably mistake for network-attested; {@code version} orders entry states and subscribers
     * observe delivery themselves.
     *
     * @param record entry state after the mutation
     * @return payload with revRegDefId, accumValue, and version
     */
    private Map<String, Object> entryEventPayload(final RevRegEntryRecord record) {
        return Map.of(
                "revRegDefId", record.getRevRegDefId(),
                "accumValue", record.getAccumValue(),
                "version", record.getVersion());
    }

    /**
     * Renders the client-declared transaction time as an ISO-8601 string.
     *
     * @return ISO-8601 transaction time
     */
    private String txTimeIso() {
        return txTimestamp.toString();
    }

    /**
     * Resolves the revocation registry entry for a revocation registry definition.
     *
     * @param revRegDefId parent revocation registry definition identifier
     * @return resolved revocation registry entry record
     * @throws VdrException if the identifier is blank or no entry exists for the definition
     */
    public RevRegEntryRecord getRevRegEntry(final String revRegDefId) {
        final String normalizedRevRegDefId =
                validationService.requireNotBlank(revRegDefId, "revRegDefId");
        final RevRegEntryRecord revRegEntryRecord = revRegEntryRegistry.get(normalizedRevRegDefId);
        if (revRegEntryRecord == null) {
            throw VdrException.notFound("revregentry", normalizedRevRegDefId);
        }
        return revRegEntryRecord;
    }
}
