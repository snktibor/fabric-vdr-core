package io.github.snktibor.vdr.logic;

import java.util.Set;

/**
 * Allowlisted {@code type} identifiers for credential definitions and revocation registry
 * definitions.
 *
 * <p>These identifiers are INVENTED BY THIS VDR. The AnonCreds v2 reference implementation
 * (hyperledger/anoncreds-v2-rs, crate {@code credx} 0.2.1) defines no wire-level scheme constant:
 * its statements are serde-tagged by Rust type name and its only scheme strings are Merlin
 * transcript domain-separation labels. Until the specification canonicalizes identifiers, this
 * registry namespaces its own as {@code <family>/<scheme-token>/<version>}, where the scheme token
 * is the reference implementation's module name — the only stable name it offers. A future
 * canonical identifier can be added to the allowlist alongside these and ours retired.
 *
 * <p>The revocation scheme is named for what the reference actually implements: the VB20
 * pairing-based accumulator (Vitto–Biryukov, <a href="https://eprint.iacr.org/2020/777">eprint
 * 2020/777</a>, {@code credx::knox::accumulator::vb20}). ALLOSAUR (eprint 2022/1362) is an
 * unimplemented multi-party witness-update layer on top of it and is deliberately not claimed here.
 */
public final class VdrTypes {

    /** BBS short-group signature scheme ({@code credx::knox::bbs}). */
    public static final String CRED_DEF_BBS_V1 = "ANONCREDS_V2/BBS/1.0";

    /** Pointcheval–Sanders short-group signature scheme ({@code credx::knox::ps}). */
    public static final String CRED_DEF_PS_V1 = "ANONCREDS_V2/PS/1.0";

    /** VB20 pairing-based accumulator revocation ({@code credx::knox::accumulator::vb20}). */
    public static final String REV_REG_VB20_V1 = "ANONCREDS_V2/VB20/1.0";

    /** Legal values for {@code CredentialDefinitionRecord.type}. */
    public static final Set<String> CRED_DEF_TYPES = Set.of(CRED_DEF_BBS_V1, CRED_DEF_PS_V1);

    /** Legal values for {@code RevRegDefRecord.type}. */
    public static final Set<String> REV_REG_DEF_TYPES = Set.of(REV_REG_VB20_V1);

    /** Utility class constructor is private to prevent instantiation. */
    private VdrTypes() {}
}
