package com.example.vdr.model;

/**
 * Lifecycle states used for append-only ledger record transitions.
 *
 * <p>Instead of deleting entries from world state, services transition records between these
 * statuses to preserve auditable history.
 */
public enum RecordStatus {
    /** Record is active and valid for normal operations. */
    ACTIVE,
    /** Record has been deactivated and should no longer be used for new flows. */
    INACTIVE
}
