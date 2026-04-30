package com.example.vdr.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.example.vdr.exception.VdrException;
import com.example.vdr.ledger.DidRegistry;
import com.example.vdr.model.DidDocument;
import com.example.vdr.model.RecordStatus;
import com.example.vdr.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
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
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link JwtValidator} DID-JWT verification behavior. */
@ExtendWith(MockitoExtension.class)
class JwtValidatorTest {

    @Mock private DidRegistry didRegistry;

    private JwtValidator jwtValidator;

    @BeforeEach
    void setUp() {
        jwtValidator = new JwtValidator(didRegistry);
    }

    /** Verifies that a properly signed ES256 JWT with a known verification method is accepted. */
    @Test
    void validateAcceptsSignedEs256Token() throws Exception {
        final KeyPair keyPair = generateP256KeyPair();
        final String issuerDid = "did:vdr:issuer";
        final String keyId = issuerDid + "#key-1";
        final DidDocument didDocument = didDocumentWithKey(issuerDid, keyId, keyPair);
        when(didRegistry.get(issuerDid)).thenReturn(didDocument);

        final Instant now = Instant.ofEpochMilli(1700000000000L);
        final String token = signEs256(keyPair, issuerDid, keyId, now);

        final JwtPrincipal principal = jwtValidator.validate(token, now);

        assertEquals(issuerDid, principal.getSignerDid());
        assertEquals(keyId, principal.getKeyId());
    }

    /** Verifies that tokens signed with unsupported algorithms are rejected. */
    @Test
    void validateRejectsNonEs256Tokens() throws Exception {
        final KeyPair keyPair = generateP256KeyPair();
        final String issuerDid = "did:vdr:issuer";
        final String keyId = issuerDid + "#key-1";
        final DidDocument didDocument = didDocumentWithKey(issuerDid, keyId, keyPair);
        // when(didRegistry.get(issuerDid)).thenReturn(didDocument);

        final Instant now = Instant.ofEpochMilli(1700000000000L);
        final SecretKey secret = Keys.hmacShaKeyFor("01234567890123456789012345678901".getBytes());
        final String token =
                Jwts.builder()
                        .header()
                        .keyId(keyId)
                        .and()
                        .issuer(issuerDid)
                        .issuedAt(Date.from(now.minusSeconds(5)))
                        .notBefore(Date.from(now.minusSeconds(5)))
                        .expiration(Date.from(now.plusSeconds(300)))
                        .signWith(secret)
                        .compact();

        assertThrows(VdrException.class, () -> jwtValidator.validate(token, now));
    }

    /** Verifies that tokens with unknown verification methods are rejected. */
    @Test
    void validateRejectsUnknownVerificationMethod() throws Exception {
        final KeyPair keyPair = generateP256KeyPair();
        final String issuerDid = "did:vdr:issuer";
        final String keyId = issuerDid + "#key-1";
        final DidDocument didDocument = didDocumentWithKey(issuerDid, keyId, keyPair);
        when(didRegistry.get(issuerDid)).thenReturn(didDocument);

        final Instant now = Instant.ofEpochMilli(1700000000000L);
        final String token = signEs256(keyPair, issuerDid, issuerDid + "#other", now);

        assertThrows(VdrException.class, () -> jwtValidator.validate(token, now));
    }

    /** Verifies that tokens with invalid signatures are rejected. */
    @Test
    void validateRejectsInvalidSignature() throws Exception {
        final KeyPair trustedKeyPair = generateP256KeyPair();
        final KeyPair attackerKeyPair = generateP256KeyPair();
        final String issuerDid = "did:vdr:issuer";
        final String keyId = issuerDid + "#key-1";
        final DidDocument didDocument = didDocumentWithKey(issuerDid, keyId, trustedKeyPair);
        when(didRegistry.get(issuerDid)).thenReturn(didDocument);

        final Instant now = Instant.ofEpochMilli(1700000000000L);
        final String token = signEs256(attackerKeyPair, issuerDid, keyId, now);

        assertThrows(VdrException.class, () -> jwtValidator.validate(token, now));
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    /** Generates a P-256 EC key pair for testing. */
    private static KeyPair generateP256KeyPair() throws Exception {
        final KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
        keyPairGenerator.initialize(new ECGenParameterSpec("secp256r1"));
        return keyPairGenerator.generateKeyPair();
    }

    /** Signs a JWT with ES256 using the provided key pair and claims. */
    private static String signEs256(
            final KeyPair keyPair, final String issuerDid, final String keyId, final Instant now) {
        return Jwts.builder()
                .header()
                .keyId(keyId)
                .and()
                .issuer(issuerDid)
                .issuedAt(Date.from(now.minusSeconds(5)))
                .notBefore(Date.from(now.minusSeconds(5)))
                .expiration(Date.from(now.plusSeconds(300)))
                .signWith(keyPair.getPrivate(), Jwts.SIG.ES256)
                .compact();
    }

    /** Constructs a DID document with a single JsonWebKey2020 verification method for testing. */
    private static DidDocument didDocumentWithKey(
            final String did, final String keyId, final KeyPair keyPair) {
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
        didDocument.setStatus(RecordStatus.ACTIVE);
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
