package io.github.snktibor.vdr.logic;

/**
 * Canonical object-type tokens carried in the AnonCreds path of a DID-bound object identifier.
 *
 * <p>Every registered AnonCreds objects method binds object identifiers to the publishing DID and
 * spells the object type as an upper-snake-case path segment — {@code did:indy} as {@code
 * <did>/anoncreds/<version>/<TYPE>/...}, the Hiero method as {@code
 * <publisher-did>/anoncreds/<version>/<TYPE>/<topic>}. These constants are this registry's
 * corresponding tokens; see {@link ValidationService#requireValidObjectId} for the grammar they sit
 * in.
 *
 * <p>{@code PUBLIC_CRED_DEF} is deliberately preferred over the {@code CLAIM_DEF} token of {@code
 * did:indy}: the latter is Indy ledger transaction vocabulary for a CL-signature credential
 * definition, an AnonCreds v1 construct this registry rejects by construction (see {@link
 * VdrTypes}). {@code PUBLIC_CRED_DEF} also states what is actually published — only the public half
 * of the credential definition ever reaches the ledger.
 */
public final class AnonCredsObjectTypes {

    /** Object-type token for schema records. */
    public static final String SCHEMA = "SCHEMA";

    /** Object-type token for credential definition records. */
    public static final String PUBLIC_CRED_DEF = "PUBLIC_CRED_DEF";

    /** Object-type token for revocation registry definition records. */
    public static final String REV_REG_DEF = "REV_REG_DEF";

    /** Utility class constructor is private to prevent instantiation. */
    private AnonCredsObjectTypes() {}
}
