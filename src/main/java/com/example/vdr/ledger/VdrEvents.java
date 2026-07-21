package com.example.vdr.ledger;

/**
 * Canonical chaincode event names emitted by VDR state-changing transactions.
 *
 * <p>Every SUBMIT transaction emits exactly one event named {@code <Entity><Verb>}. Names are
 * centralized so producers (services) and consumers (client subscriptions) share one spelling.
 * Events are delivered to subscribers together with the committed block: a transaction that fails
 * endorsement or validation delivers nothing, so an observed event always reflects committed state.
 */
public final class VdrEvents {

    /** Emitted when a DID document is registered. */
    public static final String DID_CREATED = "DidCreated";

    /** Emitted when a DID document is replaced by its owner. */
    public static final String DID_UPDATED = "DidUpdated";

    /** Emitted when a DID document is soft-deactivated by its owner. */
    public static final String DID_DEACTIVATED = "DidDeactivated";

    /** Emitted when a schema is published. */
    public static final String SCHEMA_CREATED = "SchemaCreated";

    /** Emitted when a credential definition is published. */
    public static final String CRED_DEF_CREATED = "CredDefCreated";

    /** Emitted when a revocation registry definition is published. */
    public static final String REV_REG_DEF_CREATED = "RevRegDefCreated";

    /** Emitted when the genesis revocation registry entry (accumulator) is published. */
    public static final String REV_REG_ENTRY_CREATED = "RevRegEntryCreated";

    /**
     * Emitted when a revocation registry entry's accumulator value is replaced. This is the event
     * holders of VB20-accumulator-backed credentials must watch: after every accumulator change
     * they need to refresh their witness or they can no longer produce a non-revocation proof. The
     * payload carries the new accumulator value so a subscriber needs no follow-up query.
     */
    public static final String REV_REG_ENTRY_UPDATED = "RevRegEntryUpdated";

    /** Utility class constructor is private to prevent instantiation. */
    private VdrEvents() {}
}
