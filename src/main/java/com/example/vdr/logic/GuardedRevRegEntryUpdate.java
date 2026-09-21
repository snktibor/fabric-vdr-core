package com.example.vdr.logic;

import com.example.vdr.model.RevRegEntryRecord;
import java.util.List;

/**
 * Outcome of a guarded revocation registry entry update: the mutated entry, plus the guards that
 * were checked to let it through.
 *
 * <p>The guard list is the caller's list, in submission order and with normalized identifiers, and
 * it is carried out of the service so the contract layer can report what the update was conditioned
 * on without re-reading or re-parsing anything. A guard only reaches this outcome once the version
 * read inside the transaction equalled the version it pinned, so for every element here the
 * expected version is also the version the test was decided against.
 */
public final class GuardedRevRegEntryUpdate {

    private final RevRegEntryRecord entry;
    private final List<RevRegEntryGuard> checkedGuards;

    /**
     * Creates an immutable outcome snapshot.
     *
     * @param entry entry record as persisted after the accumulator replacement
     * @param checkedGuards guards that passed, in submission order
     */
    public GuardedRevRegEntryUpdate(
            final RevRegEntryRecord entry, final List<RevRegEntryGuard> checkedGuards) {
        this.entry = entry;
        this.checkedGuards = List.copyOf(checkedGuards);
    }

    /**
     * Returns the updated entry, carrying the incremented version.
     *
     * @return persisted entry state after the mutation
     */
    public RevRegEntryRecord getEntry() {
        return entry;
    }

    /**
     * Returns the guards this update was conditioned on, in submission order.
     *
     * @return immutable list of passed guards, never empty
     */
    public List<RevRegEntryGuard> getCheckedGuards() {
        return checkedGuards;
    }
}
