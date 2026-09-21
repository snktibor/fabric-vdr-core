package com.example.vdr.logic;

/**
 * One guard of a guarded revocation registry entry update: a revocation registry entry the caller
 * observed, together with the version it must still carry for the update to commit.
 *
 * <p>The guard names a registry and pins a version; it never carries that registry's state. The
 * chaincode reads the state itself inside the transaction, which is what puts the key into the
 * transaction's read set and lets Fabric MVCC validation re-check it at commit time. A guard passed
 * in as state would prove nothing at all.
 *
 * <p>Instances are produced by {@link ValidationService#parseRevRegEntryGuards} and carry the
 * normalized (trimmed) identifier, so the value that reaches the ledger lookup is the same one that
 * reaches the response.
 */
public final class RevRegEntryGuard {

    private final String revRegDefId;
    private final long expectedVersion;

    /**
     * Creates an immutable guard reference.
     *
     * @param revRegDefId normalized identifier of the guarded revocation registry entry
     * @param expectedVersion version the caller observed and requires to be unchanged
     */
    public RevRegEntryGuard(final String revRegDefId, final long expectedVersion) {
        this.revRegDefId = revRegDefId;
        this.expectedVersion = expectedVersion;
    }

    /**
     * Returns the normalized identifier of the guarded revocation registry entry.
     *
     * @return revocation registry definition identifier the guarded entry is keyed by
     */
    public String getRevRegDefId() {
        return revRegDefId;
    }

    /**
     * Returns the version the guarded entry must still be at.
     *
     * @return expected audit version, never negative
     */
    public long getExpectedVersion() {
        return expectedVersion;
    }
}
