package io.github.snktibor.vdr.logic;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;

/**
 * Single-sourced definition of how a DID-JWT is bound to this VDR deployment and to one specific
 * transaction invocation.
 *
 * <p>Token producers (clients) and the on-chain {@link JwtValidator} MUST use the exact same
 * constants and digest algorithm from this class; the end-to-end test client imports it directly so
 * the two sides cannot drift. External clients must reproduce the following specification:
 *
 * <ul>
 *   <li>{@code aud} must contain {@link #AUDIENCE}.
 *   <li>{@code jti} must be a unique token id (for example a random UUID); each token is one-time
 *       use per issuer DID.
 *   <li>The {@value #TX_DIGEST_CLAIM} claim must equal {@link #transactionDigest}: {@code
 *       base64url-no-pad(SHA-256(LP(method) || LP(arg1) || ... || LP(argN)))} where {@code LP(s)}
 *       is the 4-byte big-endian length of the UTF-8 encoding of {@code s} followed by those UTF-8
 *       bytes. {@code method} is the plain transaction method name (no contract-name prefix, e.g.
 *       {@code "createSchema"}) and {@code arg1..argN} are the transaction's string arguments in
 *       submission order, EXCLUDING the trailing {@code jwt} argument itself. Numeric parameters
 *       use the exact wire string the client submits (canonical decimal, e.g. {@code "1000"}).
 * </ul>
 *
 * <p>Length-prefixing makes the digest input injective: no combination of method and argument
 * values can collide with a different combination, regardless of argument content.
 */
public final class JwtBinding {

    /**
     * Fixed audience identifier of this VDR deployment; every accepted token's {@code aud} claim
     * must contain it.
     */
    public static final String AUDIENCE = "vdr-network:vdrchaincode";

    /** Name of the JWT claim carrying the transaction scope digest. */
    public static final String TX_DIGEST_CLAIM = "txd";

    private static final int LENGTH_PREFIX_BYTES = 4;
    private static final int HEX_HIGH_NIBBLE_SHIFT = 4;
    private static final int NIBBLE_MASK = 0x0F;
    private static final int HEX_RADIX = 16;

    /** Utility class constructor is private to prevent instantiation. */
    private JwtBinding() {}

    /**
     * Computes the transaction scope digest binding a token to one specific invocation.
     *
     * @param method plain transaction method name without contract-name prefix
     * @param args transaction string arguments in submission order, excluding the jwt argument
     * @return base64url-encoded (unpadded) SHA-256 digest of the length-prefixed input
     */
    public static String transactionDigest(final String method, final List<String> args) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(lengthPrefixedSha256(method, args));
    }

    /**
     * Derives the deterministic world-state id under which a consumed token id is recorded.
     *
     * <p>The id is a hex digest of the length-prefixed (issuer DID, jti) pair rather than a string
     * concatenation, so no crafted issuer/jti value can collide with another pair and the resulting
     * key uses a fixed, composite-key-safe character set.
     *
     * @param issuerDid verified token issuer DID
     * @param jti token id claim value
     * @return lowercase hex SHA-256 digest identifying the (issuer, jti) pair
     */
    public static String nonceId(final String issuerDid, final String jti) {
        return toHex(lengthPrefixedSha256(issuerDid, List.of(jti)));
    }

    /**
     * Hashes a leading part and a part list as an unambiguous length-prefixed byte stream.
     *
     * @param first leading input part
     * @param rest remaining input parts in order
     * @return SHA-256 digest bytes
     */
    private static byte[] lengthPrefixedSha256(final String first, final List<String> rest) {
        final MessageDigest digest = sha256();
        updateLengthPrefixed(digest, first);
        for (final String part : rest) {
            updateLengthPrefixed(digest, part);
        }
        return digest.digest();
    }

    /**
     * Feeds one part into the digest as 4-byte big-endian UTF-8 length followed by the bytes.
     *
     * @param digest digest instance to update
     * @param part input part; {@code null} is hashed as the empty string
     */
    private static void updateLengthPrefixed(final MessageDigest digest, final String part) {
        final byte[] bytes = (part == null ? "" : part).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(LENGTH_PREFIX_BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    /**
     * Returns a SHA-256 digest instance.
     *
     * @return fresh SHA-256 {@link MessageDigest}
     */
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException exception) {
            // SHA-256 is mandatory in every conformant JRE.
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /**
     * Encodes bytes as lowercase hex.
     *
     * @param bytes input bytes
     * @return lowercase hex string
     */
    private static String toHex(final byte[] bytes) {
        final StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (final byte value : bytes) {
            hex.append(
                    Character.forDigit((value >> HEX_HIGH_NIBBLE_SHIFT) & NIBBLE_MASK, HEX_RADIX));
            hex.append(Character.forDigit(value & NIBBLE_MASK, HEX_RADIX));
        }
        return hex.toString();
    }
}
