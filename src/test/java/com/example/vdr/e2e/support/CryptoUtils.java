package com.example.vdr.e2e.support;

import com.example.vdr.e2e.config.E2EConfig;
import com.example.vdr.logic.JwtBinding;
import io.jsonwebtoken.Jwts;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Pure cryptographic helpers (key generation, DID document construction, JWT issuance). */
public final class CryptoUtils {

    private CryptoUtils() {}

    public static KeyPair generateP256KeyPair() throws GeneralSecurityException {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        return gen.generateKeyPair();
    }

    public static String keyIdForDid(final String did) {
        return did + "#key-1";
    }

    /** Builds a DID document JSON with a JsonWebKey2020 / ES256 verification method. */
    public static String buildDidJson(
            final String did, final KeyPair keyPair, final String serviceEndpoint) {
        final ECPublicKey publicKey = (ECPublicKey) keyPair.getPublic();
        final String x = base64UrlCoordinate(publicKey.getW().getAffineX().toByteArray());
        final String y = base64UrlCoordinate(publicKey.getW().getAffineY().toByteArray());

        final String serviceBlock =
                serviceEndpoint == null
                        ? ""
                        : ",\"service\":[{\"id\":\""
                                + did
                                + "#svc-1\",\"type\":\"LinkedDomains\",\"serviceEndpoint\":\""
                                + serviceEndpoint
                                + "\"}]";

        return "{"
                + "\"id\":\""
                + did
                + "\","
                + "\"controller\":\""
                + did
                + "\","
                + "\"verificationMethod\":[{"
                + "\"id\":\""
                + keyIdForDid(did)
                + "\","
                + "\"type\":\"JsonWebKey2020\","
                + "\"controller\":\""
                + did
                + "\","
                + "\"publicKeyJwk\":{"
                + "\"kty\":\"EC\","
                + "\"crv\":\"P-256\","
                + "\"x\":\""
                + x
                + "\","
                + "\"y\":\""
                + y
                + "\""
                + "}"
                + "}]"
                + serviceBlock
                + "}";
    }

    /**
     * Issues a self-signed ES256 JWT bound to {@code did} and scoped to exactly one invocation.
     *
     * <p>The token carries the claims the chaincode validator now requires: a fresh random {@code
     * jti} (one-time use), the deployment audience, and the transaction digest over the method and
     * payload arguments — all computed with the production {@link JwtBinding} definition so client
     * and chaincode canonicalization cannot drift.
     */
    public static String buildDidJwt(
            final String did,
            final KeyPair keyPair,
            final String method,
            final List<String> payloadArgs) {
        final Instant now = Instant.now();
        return Jwts.builder()
                .header()
                .keyId(keyIdForDid(did))
                .type("JWT")
                .and()
                .issuer(did)
                .subject(did)
                .id(UUID.randomUUID().toString())
                .audience()
                .add(JwtBinding.AUDIENCE)
                .and()
                .claim(
                        JwtBinding.TX_DIGEST_CLAIM,
                        JwtBinding.transactionDigest(method, payloadArgs))
                .issuedAt(Date.from(now.minusSeconds(5)))
                .notBefore(Date.from(now.minusSeconds(5)))
                .expiration(Date.from(now.plusSeconds(E2EConfig.JWT_VALIDITY_SECONDS)))
                .signWith(keyPair.getPrivate(), Jwts.SIG.ES256)
                .compact();
    }

    private static String base64UrlCoordinate(final byte[] coordinateBytes) {
        byte[] value = coordinateBytes;
        if (coordinateBytes.length == 33 && coordinateBytes[0] == 0x00) {
            value = Arrays.copyOfRange(coordinateBytes, 1, 33);
        }
        if (value.length < 32) {
            final byte[] padded = new byte[32];
            System.arraycopy(value, 0, padded, 32 - value.length, value.length);
            value = padded;
        }
        if (value.length > 32) {
            throw new IllegalStateException("Unexpected EC coordinate length: " + value.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
