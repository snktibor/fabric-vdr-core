package com.example.vdr.logic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.vdr.exception.VdrException;
import com.example.vdr.ledger.DidRegistry;
import com.example.vdr.ledger.JwtNonceRegistry;
import com.example.vdr.model.DidDocument;
import com.example.vdr.model.JwtNonceRecord;
import com.example.vdr.model.RecordStatus;
import com.example.vdr.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link JwtValidator} DID-JWT verification behavior. */
@ExtendWith(MockitoExtension.class)
class JwtValidatorTest {

    private static final String ISSUER_DID = "did:vdr:issuer";
    private static final String KEY_ID = ISSUER_DID + "#key-1";
    private static final Instant NOW = Instant.ofEpochMilli(1700000000000L);
    private static final String METHOD = "createSchema";
    private static final List<String> SCOPE_ARGS =
            List.of("schema:vdr:1", ISSUER_DID, "Degree", "1.0", "[\"name\"]");
    private static final String JTI = "3f7a1a3c-8f43-4c1e-9a35-8d1f8f8e0001";

    @Mock private DidRegistry didRegistry;

    @Mock private JwtNonceRegistry jwtNonceRegistry;

    private JwtValidator jwtValidator;

    private KeyPair keyPair;

    @BeforeEach
    void setUp() throws Exception {
        jwtValidator = new JwtValidator(didRegistry, jwtNonceRegistry);
        keyPair = generateP256KeyPair();
    }

    /** Registers an ACTIVE issuer DID document carrying the test key on the mocked registry. */
    private void givenActiveIssuerDid() {
        when(didRegistry.get(ISSUER_DID))
                .thenReturn(didDocumentWithKey(ISSUER_DID, KEY_ID, keyPair, RecordStatus.ACTIVE));
    }

    /**
     * Verifies that a fully bound (jti + aud + scope) ES256 JWT is accepted and consumes its token
     * id into the nonce registry with the token expiry.
     */
    @Test
    void validateAcceptsSignedEs256TokenAndConsumesJti() {
        givenActiveIssuerDid();
        when(jwtNonceRegistry.exists(anyString())).thenReturn(false);
        final String token = signEs256(builder -> {});

        final JwtPrincipal principal = jwtValidator.validate(token, NOW, METHOD, SCOPE_ARGS);

        assertEquals(ISSUER_DID, principal.getSignerDid());
        assertEquals(KEY_ID, principal.getKeyId());
        final ArgumentCaptor<JwtNonceRecord> recordCaptor =
                ArgumentCaptor.forClass(JwtNonceRecord.class);
        verify(jwtNonceRegistry)
                .put(eq(JwtBinding.nonceId(ISSUER_DID, JTI)), recordCaptor.capture());
        assertEquals(ISSUER_DID, recordCaptor.getValue().getIssuerDid());
        assertEquals(JTI, recordCaptor.getValue().getJti());
        assertEquals(
                NOW.plusSeconds(300).getEpochSecond(),
                recordCaptor.getValue().getExpiresAtEpochSeconds());
    }

    /** Verifies that a fragment-style kid header ("#key-1") is expanded with the issuer DID. */
    @Test
    void validateNormalizesFragmentKid() {
        givenActiveIssuerDid();
        when(jwtNonceRegistry.exists(anyString())).thenReturn(false);
        final String token = signEs256(builder -> builder.header().keyId("#key-1").and());

        final JwtPrincipal principal = jwtValidator.validate(token, NOW, METHOD, SCOPE_ARGS);

        assertEquals(KEY_ID, principal.getKeyId());
    }

    /** Verifies that tokens signed with unsupported algorithms are rejected. */
    @Test
    void validateRejectsNonEs256Tokens() {
        final SecretKey secret = Keys.hmacShaKeyFor("01234567890123456789012345678901".getBytes());
        final String token =
                boundClaims(Jwts.builder().header().keyId(KEY_ID).and()).signWith(secret).compact();

        final VdrException exception = assertUnauthorized(token, NOW);
        assertTrue(exception.getMessage().contains("alg"));
    }

    /** Verifies that tokens with unknown verification methods are rejected. */
    @Test
    void validateRejectsUnknownVerificationMethod() {
        givenActiveIssuerDid();
        final String token =
                signEs256(builder -> builder.header().keyId(ISSUER_DID + "#other").and());

        assertUnauthorized(token, NOW);
    }

    /** Verifies that tokens with invalid signatures are rejected. */
    @Test
    void validateRejectsInvalidSignature() throws Exception {
        givenActiveIssuerDid();
        final KeyPair attackerKeyPair = generateP256KeyPair();
        final String token =
                boundClaims(Jwts.builder().header().keyId(KEY_ID).and())
                        .signWith(attackerKeyPair.getPrivate(), Jwts.SIG.ES256)
                        .compact();

        assertUnauthorized(token, NOW);
    }

    /** Verifies that a token whose issuer DID is absent from the ledger is rejected. */
    @Test
    void validateRejectsMissingIssuerDid() {
        when(didRegistry.get(ISSUER_DID)).thenReturn(null);
        final String token = signEs256(builder -> {});

        final VdrException exception = assertUnauthorized(token, NOW);
        assertTrue(exception.getMessage().contains("not found"));
    }

    /** Verifies that a token whose issuer DID is INACTIVE on the ledger is rejected. */
    @Test
    void validateRejectsInactiveIssuerDid() {
        when(didRegistry.get(ISSUER_DID))
                .thenReturn(didDocumentWithKey(ISSUER_DID, KEY_ID, keyPair, RecordStatus.INACTIVE));
        final String token = signEs256(builder -> {});

        final VdrException exception = assertUnauthorized(token, NOW);
        assertTrue(exception.getMessage().contains("not active"));
    }

    /** Verifies that an expired token is rejected against the transaction timestamp. */
    @Test
    void validateRejectsExpiredToken() {
        givenActiveIssuerDid();
        final String token = signEs256(builder -> {});

        // 300s validity + 30s allowed skew; one hour later the token must be dead.
        assertUnauthorized(token, NOW.plusSeconds(3600));
    }

    /** Verifies that a token whose nbf lies in the future is rejected. */
    @Test
    void validateRejectsFutureNotBefore() {
        givenActiveIssuerDid();
        final String token =
                signEs256(builder -> builder.notBefore(Date.from(NOW.plusSeconds(120))));

        assertUnauthorized(token, NOW);
    }

    /** Verifies that a token issued in the future (beyond skew) is rejected. */
    @Test
    void validateRejectsFutureIssuedAt() {
        givenActiveIssuerDid();
        final String token =
                signEs256(builder -> builder.issuedAt(Date.from(NOW.plusSeconds(120))));

        final VdrException exception = assertUnauthorized(token, NOW);
        assertTrue(exception.getMessage().contains("iat"));
    }

    /** Verifies that each of iat, nbf and exp is individually mandatory. */
    @Test
    void validateRejectsMissingTimeClaims() {
        givenActiveIssuerDid();
        final String withoutIat = signEs256(builder -> builder.issuedAt(null));
        final String withoutNbf = signEs256(builder -> builder.notBefore(null));
        final String withoutExp = signEs256(builder -> builder.expiration(null));

        assertUnauthorized(withoutIat, NOW);
        assertUnauthorized(withoutNbf, NOW);
        assertUnauthorized(withoutExp, NOW);
    }

    /** Verifies that a token without a jti claim is rejected and consumes no nonce. */
    @Test
    void validateRejectsMissingJti() {
        givenActiveIssuerDid();
        final String token = signEs256(builder -> builder.id(null));

        final VdrException exception = assertUnauthorized(token, NOW);
        assertTrue(exception.getMessage().contains("jti"));
        verify(jwtNonceRegistry, never()).put(anyString(), any());
    }

    /** Verifies that a token whose jti was already consumed is rejected as a replay. */
    @Test
    void validateRejectsReplayedJti() {
        givenActiveIssuerDid();
        when(jwtNonceRegistry.exists(JwtBinding.nonceId(ISSUER_DID, JTI))).thenReturn(true);
        final String token = signEs256(builder -> {});

        final VdrException exception = assertUnauthorized(token, NOW);
        assertTrue(exception.getMessage().contains("replay"));
        verify(jwtNonceRegistry, never()).put(anyString(), any());
    }

    /** Verifies that missing and foreign audience values are both rejected. */
    @Test
    void validateRejectsWrongOrMissingAudience() {
        givenActiveIssuerDid();
        final String foreignAudience = signEs256(builder -> builder.claim("aud", "other-system"));
        final String missingAudience = signEs256(builder -> builder.claim("aud", null));

        assertTrue(assertUnauthorized(foreignAudience, NOW).getMessage().contains("aud"));
        assertTrue(assertUnauthorized(missingAudience, NOW).getMessage().contains("aud"));
    }

    /** Verifies that a token minted for a different method is rejected. */
    @Test
    void validateRejectsTokenScopedToDifferentMethod() {
        givenActiveIssuerDid();
        final String token = signEs256(builder -> {});

        final VdrException exception =
                assertThrows(
                        VdrException.class,
                        () -> jwtValidator.validate(token, NOW, "updateRevRegEntry", SCOPE_ARGS));
        assertEquals("UNAUTHORIZED", exception.getErrorCode());
        assertTrue(exception.getMessage().contains("scoped"));
        verify(jwtNonceRegistry, never()).put(anyString(), any());
    }

    /** Verifies that a token minted for different arguments of the same method is rejected. */
    @Test
    void validateRejectsTokenScopedToDifferentArgs() {
        givenActiveIssuerDid();
        final String token = signEs256(builder -> {});
        final List<String> tamperedArgs =
                List.of("schema:vdr:1", ISSUER_DID, "Degree", "2.0", "[\"name\"]");

        final VdrException exception =
                assertThrows(
                        VdrException.class,
                        () -> jwtValidator.validate(token, NOW, METHOD, tamperedArgs));
        assertEquals("UNAUTHORIZED", exception.getErrorCode());
    }

    /** Verifies that a token without the transaction digest claim is rejected. */
    @Test
    void validateRejectsMissingTransactionScopeClaim() {
        givenActiveIssuerDid();
        final String token = signEs256(builder -> builder.claim(JwtBinding.TX_DIGEST_CLAIM, null));

        final VdrException exception = assertUnauthorized(token, NOW);
        assertTrue(exception.getMessage().contains(JwtBinding.TX_DIGEST_CLAIM));
    }

    /** Verifies the digest definition distinguishes argument boundaries (length prefixing). */
    @Test
    void transactionDigestIsBoundaryUnambiguous() {
        final String joined = JwtBinding.transactionDigest("m", List.of("ab", "c"));
        final String shifted = JwtBinding.transactionDigest("m", List.of("a", "bc"));

        assertDoesNotThrow(() -> Base64.getUrlDecoder().decode(joined));
        assertNotEquals(joined, shifted);
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    /** Asserts the token is rejected as UNAUTHORIZED and returns the exception. */
    private VdrException assertUnauthorized(final String token, final Instant txTimestamp) {
        final VdrException exception =
                assertThrows(
                        VdrException.class,
                        () -> jwtValidator.validate(token, txTimestamp, METHOD, SCOPE_ARGS));
        assertEquals("UNAUTHORIZED", exception.getErrorCode());
        return exception;
    }

    /** Generates a P-256 EC key pair for testing. */
    private static KeyPair generateP256KeyPair() throws Exception {
        final KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
        keyPairGenerator.initialize(new ECGenParameterSpec("secp256r1"));
        return keyPairGenerator.generateKeyPair();
    }

    /**
     * Signs a fully bound ES256 JWT (issuer, kid, jti, aud, tx digest, time claims) and lets each
     * test override individual pieces through {@code customizer} before signing.
     */
    private String signEs256(final Consumer<JwtBuilder> customizer) {
        final JwtBuilder builder = boundClaims(Jwts.builder().header().keyId(KEY_ID).and());
        customizer.accept(builder);
        return builder.signWith(keyPair.getPrivate(), Jwts.SIG.ES256).compact();
    }

    /** Populates the standard claim set every well-formed test token starts from. */
    private static JwtBuilder boundClaims(final JwtBuilder builder) {
        return builder.issuer(ISSUER_DID)
                .id(JTI)
                .audience()
                .add(JwtBinding.AUDIENCE)
                .and()
                .claim(JwtBinding.TX_DIGEST_CLAIM, JwtBinding.transactionDigest(METHOD, SCOPE_ARGS))
                .issuedAt(Date.from(NOW.minusSeconds(5)))
                .notBefore(Date.from(NOW.minusSeconds(5)))
                .expiration(Date.from(NOW.plusSeconds(300)));
    }

    /** Constructs a DID document with a single JsonWebKey2020 verification method for testing. */
    private static DidDocument didDocumentWithKey(
            final String did,
            final String keyId,
            final KeyPair keyPair,
            final RecordStatus status) {
        final ECPublicKey publicKey = (ECPublicKey) keyPair.getPublic();
        final JsonNode verificationMethod =
                JsonUtil.readTree(
                        "{"
                                + "\"id\":\""
                                + keyId
                                + "\","
                                + "\"type\":\"JsonWebKey2020\","
                                + "\"controller\":\""
                                + did
                                + "\","
                                + "\"publicKeyJwk\":{"
                                + "\"kty\":\"EC\","
                                + "\"crv\":\"P-256\","
                                + "\"x\":\""
                                + base64UrlCoordinate(publicKey.getW().getAffineX().toByteArray())
                                + "\","
                                + "\"y\":\""
                                + base64UrlCoordinate(publicKey.getW().getAffineY().toByteArray())
                                + "\""
                                + "}"
                                + "}");

        final DidDocument didDocument = new DidDocument();
        didDocument.setId(did);
        didDocument.setStatus(status);
        didDocument.setVerificationMethod(List.of(verificationMethod));
        return didDocument;
    }

    /**
     * Encodes a coordinate byte array to Base64URL, handling potential leading zero for unsigned
     * representation.
     */
    private static String base64UrlCoordinate(final byte[] coordinateBytes) {
        byte[] value = coordinateBytes;
        if (coordinateBytes.length == 33 && coordinateBytes[0] == 0x00) {
            value = java.util.Arrays.copyOfRange(coordinateBytes, 1, 33);
        }
        if (value.length < 32) {
            final byte[] padded = new byte[32];
            System.arraycopy(value, 0, padded, 32 - value.length, value.length);
            value = padded;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
