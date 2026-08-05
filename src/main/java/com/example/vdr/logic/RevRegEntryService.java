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
 * version}. Fabric MVCC validation serializes concurrent submits to the same key, so the version is
 * auditable metadata rather than a concurrency guard.
 */
public class RevRegEntryService {

    private final RevRegEntryRegistry revRegEntryRegistry;
    private final RevRegDefRegistry revRegDefRegistry;
    private final ValidationService validationService;
    private final ChaincodeEventEmitter eventEmitter;
    private final Instant txTimestamp;

    /**
     * Creates a revocation registry entry service with its required dependencies.
     *
     * @param revRegEntryRegistry registry used to persist revocation registry entries
     * @param revRegDefRegistry registry used to resolve parent revocation registry definitions
     * @param validationService fail-fast input and JSON validation utility
     * @param eventEmitter chaincode event emitter for state-change notifications
     * @param txTimestamp client-declared transaction time used for audit timestamps
     */
    public RevRegEntryService(
            final RevRegEntryRegistry revRegEntryRegistry,
            final RevRegDefRegistry revRegDefRegistry,
            final ValidationService validationService,
            final ChaincodeEventEmitter eventEmitter,
            final Instant txTimestamp) {
        this.revRegEntryRegistry = revRegEntryRegistry;
        this.revRegDefRegistry = revRegDefRegistry;
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
     * @throws VdrException if validation fails, the parent definition is missing, the signer does
     *     not own the parent definition, or an entry already exists for the definition
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
        if (!parent.getOwnerId().equals(signerDid)) {
            throw VdrException.unauthorized(
                    "Unauthorized: signer DID must match the revocation registry definition owner"
                            + " for revocation registry entry creation");
        }

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
     * @throws VdrException if validation fails, the entry or its parent definition is missing, or
     *     the signer does not own the parent definition
     */
    public RevRegEntryRecord updateRevRegEntry(
            final String signerDid, final String revRegDefId, final String newAccumValue) {
        final String normalizedRevRegDefId =
                validationService.requireNotBlank(revRegDefId, "revRegDefId");
        final String normalizedAccumValue =
                validationService.requireCompressedG1Hex(newAccumValue, "accumValue");

        final RevRegEntryRecord existing = revRegEntryRegistry.get(normalizedRevRegDefId);
        if (existing == null) {
            throw VdrException.notFound("revregentry", normalizedRevRegDefId);
        }

        final RevRegDefRecord parent = revRegDefRegistry.get(normalizedRevRegDefId);
        if (parent == null) {
            throw VdrException.notFound("revregdef", normalizedRevRegDefId);
        }
        if (!parent.getOwnerId().equals(signerDid)) {
            throw VdrException.unauthorized(
                    "Unauthorized: signer DID must match the revocation registry definition owner"
                            + " for revocation registry entry update");
        }

        existing.setAccumValue(normalizedAccumValue);
        existing.setVersion(existing.getVersion() + 1);
        existing.setUpdatedAt(txTimeIso());
        revRegEntryRegistry.put(normalizedRevRegDefId, existing);
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
