package io.github.snktibor.vdr.logic;

import io.github.snktibor.vdr.model.RevRegEntryRecord;
import java.util.List;

/**
 * Outcome of a guarded revocation registry entry update: the mutated entry, plus the guards that
 * were checked to let it through.
 *
 * <p>The guard list follows the caller's submission order and carries the normalized identifiers,
 * so the contract layer can report what the update was conditioned on without re-reading or
 * re-parsing anything. Each element carries the version the chain actually read for that entry
 * inside this transaction — not the version the caller submitted — because only what the ledger
 * held is evidence.
 */
public final class GuardedRevRegEntryUpdate {

    private final RevRegEntryRecord entry;
    private final List<CheckedRevRegEntryGuard> checkedGuards;

    /**
     * Creates an immutable outcome snapshot.
     *
     * @param entry entry record as persisted after the accumulator replacement
     * @param checkedGuards guards that passed, in submission order
     */
    public GuardedRevRegEntryUpdate(
            final RevRegEntryRecord entry, final List<CheckedRevRegEntryGuard> checkedGuards) {
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
     * @return immutable list of passed guards with the versions read for them, never empty
     */
    public List<CheckedRevRegEntryGuard> getCheckedGuards() {
        return checkedGuards;
    }
}
