package com.example.vdr.logic;

import com.example.vdr.exception.VdrException;
import com.example.vdr.ledger.RevRegDefRegistry;
import com.example.vdr.ledger.RevRegEntryRegistry;
import com.example.vdr.model.RevRegDefRecord;
import com.example.vdr.model.RevRegEntryRecord;

/**
 * Layer-3 business service for revocation registry entry lifecycle operations.
 *
 * <p>This pure Java service orchestrates validation, owner-only authorization, and referential
 * integrity checks while delegating all ledger I/O to injected registries from the data-access
 * layer. The entry stores the opaque, off-chain-computed AnonCreds v2 (ALLOSAUR) accumulator value
 * verbatim: no accumulator cryptography is performed on chain and the value is never parsed. The
 * entry is the only mutable VDR record; each update replaces the accumulator value in place and
 * increments the audit {@code version}. Fabric MVCC validation serializes concurrent submits to the
 * same key, so the version is auditable metadata rather than a concurrency guard.
 */
public class RevRegEntryService {

    private final RevRegEntryRegistry revRegEntryRegistry;
    private final RevRegDefRegistry revRegDefRegistry;
    private final ValidationService validationService;

    /**
     * Creates a revocation registry entry service with its required dependencies.
     *
     * @param revRegEntryRegistry registry used to persist revocation registry entries
     * @param revRegDefRegistry registry used to resolve parent revocation registry definitions
     * @param validationService fail-fast input and JSON validation utility
     */
    public RevRegEntryService(
            final RevRegEntryRegistry revRegEntryRegistry,
            final RevRegDefRegistry revRegDefRegistry,
            final ValidationService validationService) {
        this.revRegEntryRegistry = revRegEntryRegistry;
        this.revRegDefRegistry = revRegDefRegistry;
        this.validationService = validationService;
    }

    /**
     * Creates and stores the genesis revocation registry entry for a definition.
     *
     * <p>The method enforces deterministic preconditions before persistence: required input fields,
     * UUIDv7-based id format, parent revocation registry definition existence, owner-only
     * authorization against the parent's recorded owner, and one-entry-per-definition uniqueness.
     * The {@code signerDid} argument is resolved from the verified DID-JWT upstream by the contract
     * layer; no token verification happens here.
     *
     * @param signerDid DID resolved from verified JWT issuer claim
     * @param id unique identifier for the revocation registry entry
     * @param revRegDefId parent revocation registry definition identifier
     * @param accumValue opaque genesis accumulator value computed off chain
     * @return created and persisted revocation registry entry record with version 0
     * @throws VdrException if validation fails, the parent definition is missing, the signer does
     *     not own the parent definition, or an entry already exists for the definition
     */
    public RevRegEntryRecord createRevRegEntry(
            final String signerDid,
            final String id,
            final String revRegDefId,
            final String accumValue) {
        final String normalizedId =
                validationService.requireValidEntityId(id, "revregentry:vdr:", "id");
        final String normalizedRevRegDefId =
                validationService.requireNotBlank(revRegDefId, "revRegDefId");
        final String normalizedAccumValue =
                validationService.requireNotBlank(accumValue, "accumValue");

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
                new RevRegEntryRecord(
                        normalizedId,
                        normalizedRevRegDefId,
                        normalizedAccumValue,
                        0L,
                        parent.getOwnerId());

        revRegEntryRegistry.put(normalizedRevRegDefId, revRegEntryRecord);
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
     * @param newAccumValue opaque replacement accumulator value computed off chain
     * @return updated and persisted revocation registry entry record
     * @throws VdrException if validation fails, the entry or its parent definition is missing, or
     *     the signer does not own the parent definition
     */
    public RevRegEntryRecord updateRevRegEntry(
            final String signerDid, final String revRegDefId, final String newAccumValue) {
        final String normalizedRevRegDefId =
                validationService.requireNotBlank(revRegDefId, "revRegDefId");
        final String normalizedAccumValue =
                validationService.requireNotBlank(newAccumValue, "accumValue");

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
        revRegEntryRegistry.put(normalizedRevRegDefId, existing);
        return existing;
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
