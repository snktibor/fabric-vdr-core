package com.example.vdr.logic;

import com.example.vdr.exception.VdrException;
import com.example.vdr.ledger.DidRegistry;
import com.example.vdr.model.DidDocument;
import com.example.vdr.model.RecordStatus;
import com.example.vdr.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
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

/**
 * Deterministic DID-JWT validator for state-changing chaincode transactions.
 *
 * <p>Validation is fully ledger-local: issuer DID and verification keys are resolved only from
 * world state and never through external network calls.
 */
public class JwtValidator {

    static final String ES256 = "ES256";
    static final String VERIFICATION_METHOD_TYPE = "JsonWebKey2020";
    private static final int EXPECTED_JWT_SEGMENTS = 3;
    private static final int P256_COORDINATE_BYTES = 32;
    private static final long CLOCK_SKEW_SECONDS = 30L;

    private final DidRegistry didRegistry;

    /**
     * Creates a validator backed by DID registry lookups.
     *
     * @param didRegistry DID registry used to resolve issuer key material
     */
    public JwtValidator(final DidRegistry didRegistry) {
        this.didRegistry = didRegistry;
    }

    /**
     * Validates a DID-JWT token and returns the authenticated signer principal.
     *
     * @param jwt compact JWT token
     * @param txTimestamp deterministic Fabric transaction timestamp
     * @return verified principal derived from token claims and ledger key material
     */
    public JwtPrincipal validate(final String jwt, final Instant txTimestamp) {
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
        verifySignedClaims(token, publicKey, issuerDid, txTimestamp);
        return new JwtPrincipal(issuerDid, keyId);
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
     *   <li>The token {@code iss} claim is strictly required and must equal {@code expectedIssuer}.
     * </ul>
     *
     * @param token compact JWT token
     * @param publicKey public key resolved from the issuer DID document
     * @param expectedIssuer expected issuer DID that must match the token {@code iss}
     * @param txTimestamp deterministic Fabric transaction timestamp used for time checks
     * @throws com.example.vdr.exception.VdrException when signature, issuer, or claim validations
     *     fail (returned as an unauthorized error)
     */
    private void verifySignedClaims(
            final String token,
            final PublicKey publicKey,
            final String expectedIssuer,
            final Instant txTimestamp) {
        try {
            final Claims verifiedClaims =
                    Jwts.parser()
                            .verifyWith(publicKey)
                            .clock(() -> Date.from(txTimestamp))
                            .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                            .requireIssuer(expectedIssuer)
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
        } catch (final SignatureException exception) {
            throw unauthorized("invalid jwt signature");
        } catch (final JwtException | IllegalArgumentException exception) {
            throw unauthorized("jwt validation failed: " + safeMessage(exception));
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
     * @throws com.example.vdr.exception.VdrException when the verification method is missing,
     *     malformed, or uses an unsupported type
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
     * @throws com.example.vdr.exception.VdrException when the JWK is not EC/P-256, coordinates are
     *     invalid, or key construction fails
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
     * @throws com.example.vdr.exception.VdrException when decoding or parsing fails
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
     * @throws com.example.vdr.exception.VdrException when {@code kid} is missing or blank
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
     * @throws com.example.vdr.exception.VdrException when the value is not valid base64url
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
     * @throws com.example.vdr.exception.VdrException when the field is missing or blank
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
     * Returns a stable, non-null message for exceptions used in error responses.
     *
     * <p>When an exception has no useful message this method returns the exception class name to
     * avoid leaking implementation details while preserving diagnosability.
     *
     * @param exception exception to extract a safe message from
     * @return non-empty message string
     */
    private static String safeMessage(final Exception exception) {
        final String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
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
