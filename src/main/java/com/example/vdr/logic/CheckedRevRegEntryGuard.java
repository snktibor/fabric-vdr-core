package com.example.vdr.logic;

/**
 * One guard that passed, reported back to the caller: the guarded revocation registry entry and the
 * version the test was actually decided against.
 *
 * <p>The version here is the one read out of world state inside the transaction, not the one the
 * caller submitted. The two are equal for every guard that passes — that equality is the test — but
 * only the value the chain read is evidence of anything, so only that value is carried out. For a
 * guard naming the entry being updated, it is that entry's version before the mutation, and
 * therefore exactly one less than the version the response reports for the entry itself.
 *
 * <p>The type deliberately carries no expected version. What the caller asked for is the caller's
 * own record; what the ledger held is the part the caller cannot otherwise know.
 */
public final class CheckedRevRegEntryGuard {

    private final String revRegDefId;
    private final long checkedVersion;

    /**
     * Creates an immutable record of one passed guard.
     *
     * @param revRegDefId normalized identifier of the guarded revocation registry entry
     * @param checkedVersion version read for that entry inside this transaction
     */
    public CheckedRevRegEntryGuard(final String revRegDefId, final long checkedVersion) {
        this.revRegDefId = revRegDefId;
        this.checkedVersion = checkedVersion;
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
     * Returns the version read for the guarded entry inside this transaction.
     *
     * @return version the guard test was decided against
     */
    public long getCheckedVersion() {
        return checkedVersion;
    }
}
