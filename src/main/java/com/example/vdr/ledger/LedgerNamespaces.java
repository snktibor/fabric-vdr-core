package com.example.vdr.ledger;

/**
 * Canonical composite-key object type prefixes for VDR ledger records.
 *
 * <p>These constants define deterministic namespace segments used by layer-4 registries when
 * creating Fabric composite keys. Centralizing them prevents accidental key collisions and keeps
 * storage conventions consistent across DID, schema, and credential definition entities.
 */
public final class LedgerNamespaces {

    /** Composite-key namespace for DID documents. */
    public static final String DID = "DID";

    /** Composite-key namespace for schema records. */
    public static final String SCHEMA = "SCHEMA";

    /** Composite-key namespace for credential definition records. */
    public static final String CRED_DEF = "CREDDEF";

    /** Utility class constructor is private to prevent instantiation. */
    private LedgerNamespaces() {}
}
