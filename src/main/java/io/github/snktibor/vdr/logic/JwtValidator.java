package io.github.snktibor.vdr.logic;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.snktibor.vdr.exception.VdrException;
import io.github.snktibor.vdr.ledger.DidRegistry;
import io.github.snktibor.vdr.ledger.JwtNonceRegistry;
import io.github.snktibor.vdr.model.DidDocument;
import io.github.snktibor.vdr.model.JwtNonceRecord;
import io.github.snktibor.vdr.model.RecordStatus;
import io.github.snktibor.vdr.util.JsonUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.SignatureException;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Deterministic DID-JWT validator for state-changing chaincode transactions.
 *
 * <p>Validation is fully ledger-local: issuer DID and verification keys are resolved only from
 * world state and never through external network calls.
 *
 * <p>Beyond signature and time-claim checks, accepted tokens must be bound to this deployment and
 * to the exact invocation they authorize (see {@link JwtBinding}), and are one-time use:
 *
 * <ul>
 *   <li>{@code aud} must contain {@link JwtBinding#AUDIENCE}, so tokens minted for other systems
 *       are rejected even when signed with the same DID key.
 *   <li>The {@value JwtBinding#TX_DIGEST_CLAIM} claim must match the digest of the invoked method
 *       and its submitted arguments, so a token captured from one transaction (all submitted JWTs
 *       are readable from the block store) cannot authorize a different operation.
 *   <li>{@code jti} is mandatory and consumed into world state on success, so an identical token
 *       cannot be replayed at all — including by a malicious client back-dating the client-supplied
 *       transaction timestamp to re-enter the token's validity window.
 * </ul>
 */
public class JwtValidator {

    static final String ES256 = "ES256";
    static final String VERIFICATION_METHOD_TYPE = "JsonWebKey2020";
    private static final int EXPECTED_JWT_SEGMENTS = 3;
    private static final int P256_COORDINATE_BYTES = 32;
    private static final long CLOCK_SKEW_SECONDS = 30L;

    private final DidRegistry didRegistry;
    private final JwtNonceRegistry jwtNonceRegistry;

    /**
     * Creates a validator backed by ledger registry lookups.
     *
     * @param didRegistry DID registry used to resolve issuer key material
     * @param jwtNonceRegistry registry recording consumed token ids for replay protection
     */
    public JwtValidator(final DidRegistry didRegistry, final JwtNonceRegistry jwtNonceRegistry) {
        this.didRegistry = didRegistry;
        this.jwtNonceRegistry = jwtNonceRegistry;
    }

    /**
     * Validates a DID-JWT token against the invoked transaction and returns the authenticated
     * signer principal, consuming the token's {@code jti} so it cannot be used again.
     *
     * <p>This method writes a nonce record to world state and therefore must only run on SUBMIT
     * transactions; read-only EVALUATE transactions take no JWT. The write is part of the
     * transaction's write set: it persists only when the whole transaction commits, and Fabric MVCC
     * invalidates the second of two concurrent submissions consuming the same token id.
     *
     * @param jwt compact JWT token
     * @param txTimestamp deterministic transaction timestamp from the client's channel header
     * @param method plain transaction method name being invoked (no contract-name prefix)
     * @param scopeArgs submitted transaction arguments in order, excluding the jwt argument
     * @return verified principal derived from token claims and ledger key material
     */
    public JwtPrincipal validate(
            final String jwt,
            final Instant txTimestamp,
            final String method,
            final List<String> scopeArgs) {
        final String token = JsonUtil.trimToNull(jwt);
        if (token == null) {
            throw unauthorized("missing jwt token");
        }

        final JsonNode header = parsePartAsJsonObject(token, 0, "header");
        final JsonNode claims = parsePartAsJsonObject(token, 1, "claims");

        final String alg = requireText(header, "alg", "jwt header");
        if (!ES256.equals(alg)) {
            throw unauthorized("unsupported jwt alg: " + alg);
        }

        final String issuerDid = requireText(claims, Claims.ISSUER, "jwt claims");
        final String keyId = normalizeKid(requireText(header, "kid", "jwt header"), issuerDid);

        final DidDocument didDocument = didRegistry.get(issuerDid);
        if (didDocument == null) {
            throw unauthorized("issuer DID not found on ledger: " + issuerDid);
        }
        if (didDocument.getStatus() != RecordStatus.ACTIVE) {
            throw unauthorized("issuer DID is not active: " + issuerDid);
        }

        final JsonNode verificationMethod = resolveVerificationMethod(didDocument, keyId);
        final PublicKey publicKey = toPublicKey(verificationMethod, keyId);
        final Claims verifiedClaims = verifySignedClaims(token, publicKey, txTimestamp);
        requireAudience(verifiedClaims);
        requireTransactionScope(verifiedClaims, method, scopeArgs);
        consumeTokenId(verifiedClaims, issuerDid);
        return new JwtPrincipal(issuerDid, keyId);
    }

    /**
     * Requires the verified {@code aud} claim to contain this deployment's audience identifier.
     *
     * @param verifiedClaims signature-verified token claims
     * @throws io.github.snktibor.vdr.exception.VdrException when the audience is missing or foreign
     */
    private void requireAudience(final Claims verifiedClaims) {
        final Set<String> audience = verifiedClaims.getAudience();
        if (audience == null || !audience.contains(JwtBinding.AUDIENCE)) {
            throw unauthorized("jwt aud must contain '" + JwtBinding.AUDIENCE + "'");
        }
    }

    /**
     * Requires the verified transaction digest claim to match the invocation being authorized.
     *
     * <p>The expected digest is recomputed on chain from the invoked method name and the submitted
     * arguments using the shared {@link JwtBinding} definition, so a token minted for one operation
     * (or the same operation with different arguments) is rejected on any other.
     *
     * @param verifiedClaims signature-verified token claims
     * @param method plain transaction method name being invoked
     * @param scopeArgs submitted transaction arguments excluding the jwt argument
     * @throws io.github.snktibor.vdr.exception.VdrException when the digest claim is missing or
     *     does not match the invocation
     */
    private void requireTransactionScope(
            final Claims verifiedClaims, final String method, final List<String> scopeArgs) {
        final String claimedDigest =
                JsonUtil.trimToNull(verifiedClaims.get(JwtBinding.TX_DIGEST_CLAIM, String.class));
        if (claimedDigest == null) {
            throw unauthorized(
                    "jwt must include the '" + JwtBinding.TX_DIGEST_CLAIM + "' scope claim");
        }
        final String expectedDigest = JwtBinding.transactionDigest(method, scopeArgs);
        if (!expectedDigest.equals(claimedDigest)) {
            throw unauthorized("jwt is not scoped to this transaction (method or args mismatch)");
        }
    }

    /**
     * Enforces one-time token use by consuming the mandatory {@code jti} claim into world state.
     *
     * <p>If a nonce record for this (issuer DID, jti) pair already exists the token was used before
     * and the transaction is rejected as a replay. Otherwise the pair is recorded together with the
     * token expiry, keeping records prunable once their replay window has closed. Fabric MVCC
     * serializes concurrent submissions of the same pair: the absent-key read is part of the read
     * set, so only the first commit survives validation.
     *
     * @param verifiedClaims signature-verified token claims (expiry known to be present)
     * @param issuerDid verified issuer DID
     * @throws io.github.snktibor.vdr.exception.VdrException when {@code jti} is missing or already
     *     used
     */
    private void consumeTokenId(final Claims verifiedClaims, final String issuerDid) {
        final String jti = JsonUtil.trimToNull(verifiedClaims.getId());
        if (jti == null) {
            throw unauthorized("jwt must include a jti claim");
        }

        final String nonceId = JwtBinding.nonceId(issuerDid, jti);
        if (jwtNonceRegistry.exists(nonceId)) {
            throw unauthorized("jwt token id has already been used (replay rejected)");
        }

        final long expiresAtEpochSeconds =
                verifiedClaims.getExpiration().toInstant().getEpochSecond();
        jwtNonceRegistry.put(nonceId, new JwtNonceRecord(issuerDid, jti, expiresAtEpochSeconds));
    }

    /**
     * Verifies the JWT signature and standard time-based claims using ledger-determined time.
     *
     * <p>Validation rules applied here are central to the VDR authorization model:
     *
     * <ul>
     *   <li>The token signature MUST validate with the {@code publicKey} (ES256/P-256).
     *   <li>Time claims {@code iat}, {@code nbf} and {@code exp} are evaluated against the
     *       deterministic {@code txTimestamp} (Fabric transaction time) and allow a small {@code
     *       CLOCK_SKEW_SECONDS} tolerance; system clock MUST NOT be used.
     * </ul>
     *
     * <p>The issuer is not re-checked here: {@code publicKey} was resolved from the DID document of
     * the {@code iss} claim itself, so a successful signature verification is exactly the statement
     * that the token was signed by a key the claimed issuer published.
     *
     * @param token compact JWT token
     * @param publicKey public key resolved from the issuer DID document
     * @param txTimestamp deterministic Fabric transaction timestamp used for time checks
     * @return signature-verified token claims for further deployment/scope/nonce checks
     * @throws io.github.snktibor.vdr.exception.VdrException when signature or claim validations
     *     fail (returned as an unauthorized error)
     */
    private Claims verifySignedClaims(
            final String token, final PublicKey publicKey, final Instant txTimestamp) {
        try {
            final Claims verifiedClaims =
                    Jwts.parser()
                            .verifyWith(publicKey)
                            .clock(() -> Date.from(txTimestamp))
                            .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                            .build()
                            .parseSignedClaims(token)
                            .getPayload();

            if (verifiedClaims.getIssuedAt() == null
                    || verifiedClaims.getNotBefore() == null
                    || verifiedClaims.getExpiration() == null) {
                throw unauthorized("jwt must include iat, nbf, and exp claims");
            }

            final Instant maxNow = txTimestamp.plusSeconds(CLOCK_SKEW_SECONDS);
            if (verifiedClaims.getIssuedAt().toInstant().isAfter(maxNow)) {
                throw unauthorized("jwt iat is in the future");
            }
            return verifiedClaims;
        } catch (final SignatureException exception) {
            throw unauthorized("invalid jwt signature");
        } catch (final JwtException | IllegalArgumentException exception) {
            throw unauthorized("jwt validation failed: " + JsonUtil.safeMessage(exception));
        }
    }

    /**
     * Finds and validates the verification method entry for {@code keyId} inside a DID document.
     *
     * <p>The method enforces ledger-local checks: the verification method must exist, declare a
     * supported {@code type} ({@code JsonWebKey2020}) and include a {@code publicKeyJwk} JSON
     * object containing the key material.
     *
     * @param document DID document retrieved from ledger world state
     * @param keyId verification method identifier to locate (may be DID-prefixed)
     * @return the {@code publicKeyJwk} JSON node for the requested verification method
     * @throws io.github.snktibor.vdr.exception.VdrException when the verification method is
     *     missing, malformed, or uses an unsupported type
     */
    private JsonNode resolveVerificationMethod(final DidDocument document, final String keyId) {
        if (document.getVerificationMethod() == null
                || document.getVerificationMethod().isEmpty()) {
            throw unauthorized("issuer DID has no verification methods");
        }

        for (final JsonNode method : document.getVerificationMethod()) {
            if (method == null || !method.isObject()) {
                continue;
            }

            final String methodId = requireText(method, "id", "verificationMethod entry");
            if (!keyId.equals(methodId)) {
                continue;
            }

            final String type = requireText(method, "type", "verificationMethod entry");
            if (!VERIFICATION_METHOD_TYPE.equals(type)) {
                throw unauthorized("unsupported verification method type: " + type);
            }

            final JsonNode jwk = method.get("publicKeyJwk");
            if (jwk == null || !jwk.isObject()) {
                throw unauthorized("verificationMethod '" + methodId + "' has no publicKeyJwk");
            }
            return jwk;
        }

        throw unauthorized("verification method not found for kid: " + keyId);
    }

    /**
     * Converts a JWK publicKeyJwk node to a Java {@link PublicKey} instance.
     *
     * <p>This implementation supports only EC keys on the P-256 curve (ES256). It decodes the
     * base64url-encoded {@code x} and {@code y} coordinates, verifies expected lengths and
     * constructs an {@code ECPublicKeySpec} using the named curve parameters from the JDK.
     *
     * @param jwk JWK object node containing {@code kty}, {@code crv}, {@code x} and {@code y}
     * @param keyId verification method id used for error context
     * @return constructed EC {@link PublicKey}
     * @throws io.github.snktibor.vdr.exception.VdrException when the JWK is not EC/P-256,
     *     coordinates are invalid, or key construction fails
     */
    private PublicKey toPublicKey(final JsonNode jwk, final String keyId) {
        final String keyType = requireText(jwk, "kty", "publicKeyJwk");
        final String curve = requireText(jwk, "crv", "publicKeyJwk");
        if (!"EC".equals(keyType) || !"P-256".equals(curve)) {
            throw unauthorized(
                    "verificationMethod '" + keyId + "' must use EC P-256 JWK coordinates");
        }

        final byte[] x = decodeCoordinate(requireText(jwk, "x", "publicKeyJwk"), "x");
        final byte[] y = decodeCoordinate(requireText(jwk, "y", "publicKeyJwk"), "y");

        if (x.length != P256_COORDINATE_BYTES || y.length != P256_COORDINATE_BYTES) {
            throw unauthorized("publicKeyJwk coordinates must be 32-byte values for P-256");
        }

        try {
            final AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            final ECParameterSpec ecSpec = parameters.getParameterSpec(ECParameterSpec.class);
            final ECPoint point =
                    new ECPoint(new java.math.BigInteger(1, x), new java.math.BigInteger(1, y));
            final ECPublicKeySpec keySpec = new ECPublicKeySpec(point, ecSpec);
            return KeyFactory.getInstance("EC").generatePublic(keySpec);
        } catch (final Exception exception) {
            throw unauthorized("unable to construct EC public key from DID document");
        }
    }

    /**
     * Parses a specific dot-separated JWT part (header, claims) as a JSON object.
     *
     * <p>Performs base64url decoding and JSON parsing while enforcing exact JWT segment structure.
     * Errors are mapped to unauthorized domain errors to ensure callers treat malformed tokens
     * uniformly.
     *
     * @param token compact JWT token
     * @param partIndex index of the JWT part to parse (0=header, 1=claims, 2=signature)
     * @param partName human-readable part name used in error messages
     * @return parsed JSON object node
     * @throws io.github.snktibor.vdr.exception.VdrException when decoding or parsing fails
     */
    private JsonNode parsePartAsJsonObject(
            final String token, final int partIndex, final String partName) {
        final String[] parts = token.split("\\.", -1);
        if (parts.length != EXPECTED_JWT_SEGMENTS) {
            throw unauthorized("malformed jwt token");
        }

        try {
            final byte[] decoded = Base64.getUrlDecoder().decode(parts[partIndex]);
            final JsonNode node = JsonUtil.readTree(new String(decoded, StandardCharsets.UTF_8));
            if (!node.isObject()) {
                throw unauthorized("jwt " + partName + " must be a JSON object");
            }
            return node;
        } catch (final IllegalArgumentException exception) {
            throw unauthorized("jwt " + partName + " is not valid base64url");
        } catch (final VdrException exception) {
            throw unauthorized("jwt " + partName + " is not valid JSON");
        }
    }

    /**
     * Normalizes the JWT {@code kid} header value to a full verification method identifier.
     *
     * <p>If the {@code kid} is a fragment (starts with '#') it is expanded by prefixing the {@code
     * issuerDid}. Trims and rejects blank values.
     *
     * @param kid raw {@code kid} header from JWT
     * @param issuerDid issuer DID used to expand fragment identifiers
     * @return normalized verification method id
     * @throws io.github.snktibor.vdr.exception.VdrException when {@code kid} is missing or blank
     */
    private String normalizeKid(final String kid, final String issuerDid) {
        final String normalized = JsonUtil.trimToNull(kid);
        if (normalized == null) {
            throw unauthorized("missing jwt kid header");
        }
        if (normalized.startsWith("#")) {
            return issuerDid + normalized;
        }
        return normalized;
    }

    /**
     * Decodes a base64url-encoded JWK coordinate value.
     *
     * @param value base64url-encoded coordinate
     * @param fieldName name of the coordinate field (for error messages)
     * @return decoded raw bytes
     * @throws io.github.snktibor.vdr.exception.VdrException when the value is not valid base64url
     */
    private byte[] decodeCoordinate(final String value, final String fieldName) {
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (final IllegalArgumentException exception) {
            throw unauthorized("publicKeyJwk field '" + fieldName + "' is not valid base64url");
        }
    }

    /**
     * Extracts and validates a non-blank textual field from a JSON node.
     *
     * @param node JSON node to read from
     * @param field field name to extract
     * @param sourceName logical source used in error messages (e.g. "jwt header")
     * @return trimmed non-blank text value
     * @throws io.github.snktibor.vdr.exception.VdrException when the field is missing or blank
     */
    private String requireText(final JsonNode node, final String field, final String sourceName) {
        final JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw unauthorized("missing jwt field '" + field + "' in " + sourceName);
        }

        final String text = JsonUtil.trimToNull(value.asText());
        if (text == null) {
            throw unauthorized("blank jwt field '" + field + "' in " + sourceName);
        }
        return text;
    }

    /**
     * Helper that creates a domain {@link VdrException} representing an unauthorized error.
     *
     * @param reason short reason used to build the exception message
     * @return {@link VdrException} representing unauthorized access
     */
    private static VdrException unauthorized(final String reason) {
        return VdrException.unauthorized("authorization failed: " + reason);
    }
}
