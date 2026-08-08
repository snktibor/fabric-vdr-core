package com.example.vdr.logic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.vdr.TestVectors;
import com.example.vdr.exception.VdrException;
import com.example.vdr.model.DidDocument;
import com.example.vdr.util.JsonUtil;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ValidationService} fail-fast validation helpers.
 *
 * <p>Covers string normalization, JSON shape enforcement, and schema-attribute compatibility checks
 * used by domain services.
 */
@SuppressWarnings("java:S1192")
class ValidationServiceTest {

    private static final String ISSUER_DID = "did:vdr:018f6c02-3a5e-7cbb-9d3e-5f2a4b6c8d0e";

    private static final String SCHEMA_ID =
            ISSUER_DID + "/anoncreds/v2/SCHEMA/018f6c05-1b22-7aa0-8c31-9e77d0f6a4b1";

    private final ValidationService validationService = new ValidationService();

    /** Verifies blank values are rejected while surrounding whitespace is trimmed. */
    @Test
    void requireNotBlankTrimsAndRejects() {
        assertEquals("id", validationService.requireNotBlank(" id ", "id"));
        assertThrows(VdrException.class, () -> validationService.requireNotBlank("  ", "id"));
    }

    /** Verifies identifier matching trims payload values and rejects mismatches. */
    @Test
    void requireMatchingIdsTrimsActual() {
        validationService.requireMatchingIds("id", " id ", "id");
        assertThrows(
                VdrException.class,
                () -> validationService.requireMatchingIds("id", "other", "id"));
    }

    /** Verifies well-formed DIDs of any method are accepted and returned trimmed. */
    @Test
    void requireValidDidAcceptsAnyMethod() {
        assertEquals(ISSUER_DID, validationService.requireValidDid(" " + ISSUER_DID + " ", "id"));
        assertEquals(
                "did:web:issuer.example.com",
                validationService.requireValidDid("did:web:issuer.example.com", "id"));
        assertEquals("did:example:123", validationService.requireValidDid("did:example:123", "id"));
    }

    /** Verifies a DID carrying a multi-segment method-specific id is accepted. */
    @Test
    void requireValidDidAcceptsColonSegmentedMethodSpecificId() {
        assertEquals(
                "did:indy:sovrin:staging:6cgbu8ZPoWTnR5Rv5JcSMB",
                validationService.requireValidDid(
                        "did:indy:sovrin:staging:6cgbu8ZPoWTnR5Rv5JcSMB", "id"));
    }

    /** Verifies strings that are not well-formed DIDs are rejected as validation failures. */
    @Test
    void requireValidDidRejectsMalformedValues() {
        // No method-specific id at all.
        assertThrows(VdrException.class, () -> validationService.requireValidDid("did:vdr", "id"));
        // Empty method-specific id.
        assertThrows(VdrException.class, () -> validationService.requireValidDid("did:vdr:", "id"));
        // Uppercase method name violates the DID Core method-name production.
        assertThrows(
                VdrException.class, () -> validationService.requireValidDid("did:VDR:abc", "id"));
        // A DID never contains a path segment.
        assertThrows(
                VdrException.class,
                () -> validationService.requireValidDid(ISSUER_DID + "/anoncreds", "id"));
        // Not a DID at all — the shape the end-to-end suite used to mint for issuer seeds.
        assertThrows(
                VdrException.class,
                () ->
                        validationService.requireValidDid(
                                "issuer-did-018f6c02-3a5e-7cbb-9d3e-5f2a4b6c8d0e", "id"));
    }

    /** Verifies a DID URL rooted at the issuer DID is accepted and returned trimmed. */
    @Test
    void requireValidObjectIdAcceptsIssuerRootedDidUrl() {
        assertEquals(
                SCHEMA_ID,
                validationService.requireValidObjectId(
                        " " + SCHEMA_ID + " ", ISSUER_DID, AnonCredsObjectTypes.SCHEMA, "id"));
    }

    /** Verifies an id rooted at a different issuer DID is rejected as a validation failure. */
    @Test
    void requireValidObjectIdRejectsForeignIssuer() {
        assertThrows(
                VdrException.class,
                () ->
                        validationService.requireValidObjectId(
                                SCHEMA_ID,
                                "did:vdr:018f6c09-2c55-7d61-b4a8-6f03e19b7c22",
                                AnonCredsObjectTypes.SCHEMA,
                                "id"));
    }

    /** Verifies an id carrying the wrong object-type token is rejected. */
    @Test
    void requireValidObjectIdRejectsWrongObjectType() {
        assertThrows(
                VdrException.class,
                () ->
                        validationService.requireValidObjectId(
                                SCHEMA_ID, ISSUER_DID, AnonCredsObjectTypes.PUBLIC_CRED_DEF, "id"));
    }

    /** Verifies an id whose object segment is not a UUIDv7 is rejected as a validation failure. */
    @Test
    void requireValidObjectIdRejectsNonUuidV7() {
        assertThrows(
                VdrException.class,
                () ->
                        validationService.requireValidObjectId(
                                ISSUER_DID + "/anoncreds/v2/SCHEMA/not-a-uuid-v7",
                                ISSUER_DID,
                                AnonCredsObjectTypes.SCHEMA,
                                "id"));
    }

    /** Verifies the legacy prefixed-URN identifier shape is no longer accepted. */
    @Test
    void requireValidObjectIdRejectsLegacyPrefixedId() {
        assertThrows(
                VdrException.class,
                () ->
                        validationService.requireValidObjectId(
                                "schema:vdr:018f6c05-1b22-7aa0-8c31-9e77d0f6a4b1",
                                ISSUER_DID,
                                AnonCredsObjectTypes.SCHEMA,
                                "id"));
    }

    /** Verifies object parsing helper rejects JSON arrays. */
    @Test
    void parseJsonObjectRejectsArray() {
        assertThrows(VdrException.class, () -> validationService.parseJsonObject("[1]", "payload"));
    }

    /** Verifies string-array parser rejects duplicate entries. */
    @Test
    void parseStringArrayRejectsDuplicates() {
        assertThrows(
                VdrException.class,
                () -> validationService.parseStringArray("[\"a\",\"a\"]", "attrNames"));
    }

    /** Verifies string-array parser rejects non-text and empty arrays. */
    @Test
    void parseStringArrayRejectsNonStringAndEmpty() {
        assertThrows(
                VdrException.class, () -> validationService.parseStringArray("[1]", "attrNames"));
        assertThrows(
                VdrException.class, () -> validationService.parseStringArray("[]", "attrNames"));
    }

    /** Verifies string-array parser returns normalized values for valid input. */
    @Test
    void parseStringArrayReturnsValues() {
        assertEquals(
                List.of("a", "b"),
                validationService.parseStringArray("[\"a\",\"b\"]", "attrNames"));
    }

    /** Verifies DID document shape validation rejects non-object entries. */
    @Test
    void validateDidDocumentShapeRejectsNonObjectElements() {
        final DidDocument document = new DidDocument();
        document.setVerificationMethod(List.of(TextNode.valueOf("bad")));

        assertThrows(
                VdrException.class, () -> validationService.validateDidDocumentShape(document));
    }

    /** Verifies DID document shape validation requires verification methods. */
    @Test
    void validateDidDocumentShapeRejectsMissingVerificationMethods() {
        final DidDocument document = new DidDocument();

        assertThrows(
                VdrException.class, () -> validationService.validateDidDocumentShape(document));
    }

    /** Verifies DID document shape validation accepts JsonWebKey2020 P-256 method entries. */
    @Test
    void validateDidDocumentShapeAcceptsJsonWebKey2020() {
        final DidDocument document = new DidDocument();
        document.setVerificationMethod(
                List.of(
                        JsonUtil.readTree(
                                "{"
                                        + "\"id\":\"did:example:123#key-1\","
                                        + "\"type\":\"JsonWebKey2020\","
                                        + "\"controller\":\"did:example:123\","
                                        + "\"publicKeyJwk\":{"
                                        + "\"kty\":\"EC\","
                                        + "\"crv\":\"P-256\","
                                        + "\"x\":\"f83OJ3D2xF4P6sY0j8B1Q9QXc7Tx8U7R8G5C7jS1m3A\","
                                        + "\"y\":\"x_FEzRu9q4hM9M4f6jQ9QvV4fru3c6JQnSg0dW4mN18\""
                                        + "}}")));

        assertDoesNotThrow(() -> validationService.validateDidDocumentShape(document));
    }

    /** Verifies allowlisted type values pass and foreign values are rejected. */
    @Test
    void requireAllowedTypeEnforcesAllowlist() {
        final Set<String> allowed = VdrTypes.REV_REG_DEF_TYPES;

        assertEquals(
                VdrTypes.REV_REG_VB20_V1,
                validationService.requireAllowedType(
                        " " + VdrTypes.REV_REG_VB20_V1 + " ", allowed, "type"));

        final VdrException v1Construct =
                assertThrows(
                        VdrException.class,
                        () -> validationService.requireAllowedType("CL_ACCUM", allowed, "type"));
        assertEquals("INVALID_ARGUMENT", v1Construct.getErrorCode());
        assertThrows(
                VdrException.class,
                () -> validationService.requireAllowedType("ALLOSAUR", allowed, "type"));
    }

    /** Verifies the canonical compressed G1 form is accepted and every variant is rejected. */
    @Test
    void requireCompressedG1HexAcceptsCanonicalFormOnly() {
        assertEquals(
                TestVectors.G1_GENERATOR,
                validationService.requireCompressedG1Hex(TestVectors.G1_GENERATOR, "accumValue"));

        // Uppercase, prefixes, wrong lengths, non-hex, base64.
        assertThrows(
                VdrException.class,
                () ->
                        validationService.requireCompressedG1Hex(
                                TestVectors.G1_GENERATOR.toUpperCase(), "accumValue"));
        assertThrows(
                VdrException.class,
                () ->
                        validationService.requireCompressedG1Hex(
                                "0x" + TestVectors.G1_GENERATOR, "accumValue"));
        assertThrows(
                VdrException.class,
                () ->
                        validationService.requireCompressedG1Hex(
                                TestVectors.G1_GENERATOR.substring(2), "accumValue"));
        assertThrows(
                VdrException.class,
                () ->
                        validationService.requireCompressedG1Hex(
                                TestVectors.G1_GENERATOR + TestVectors.G1_GENERATOR, "accumValue"));
        assertThrows(
                VdrException.class,
                () -> validationService.requireCompressedG1Hex("not-hex", "accumValue"));

        // Compression flag missing (uncompressed-style flag byte).
        final String uncompressedFlag = "17" + TestVectors.G1_GENERATOR.substring(2);
        final VdrException noFlag =
                assertThrows(
                        VdrException.class,
                        () ->
                                validationService.requireCompressedG1Hex(
                                        uncompressedFlag, "accumValue"));
        assertEquals("INVALID_ARGUMENT", noFlag.getErrorCode());

        // The identity element (infinity flag) is rejected outright.
        final String identity = "c0" + "0".repeat(94);
        final VdrException identityRejected =
                assertThrows(
                        VdrException.class,
                        () -> validationService.requireCompressedG1Hex(identity, "accumValue"));
        assertEquals("INVALID_ARGUMENT", identityRejected.getErrorCode());
    }

    /** Verifies the compressed G2 checks mirror G1 at 192 hex characters. */
    @Test
    void requireCompressedG2HexAcceptsCanonicalFormOnly() {
        assertEquals(
                TestVectors.G2_GENERATOR,
                validationService.requireCompressedG2Hex(TestVectors.G2_GENERATOR, "key"));

        assertThrows(
                VdrException.class,
                () -> validationService.requireCompressedG2Hex(TestVectors.G1_GENERATOR, "key"));
        assertThrows(
                VdrException.class,
                () -> validationService.requireCompressedG2Hex("c0" + "0".repeat(190), "key"));
    }

    /** Verifies the publicKeys object must contain exactly the verifying key field. */
    @Test
    void requireRevocationPublicKeysEnforcesExactShape() {
        assertDoesNotThrow(
                () ->
                        validationService.requireRevocationPublicKeys(
                                TestVectors.REVOCATION_PUBLIC_KEYS_JSON, "publicKeys"));

        assertThrows(
                VdrException.class,
                () -> validationService.requireRevocationPublicKeys("{}", "publicKeys"));
        assertThrows(
                VdrException.class,
                () ->
                        validationService.requireRevocationPublicKeys(
                                "{\"otherKey\":\"" + TestVectors.G2_GENERATOR + "\"}",
                                "publicKeys"));
        assertThrows(
                VdrException.class,
                () ->
                        validationService.requireRevocationPublicKeys(
                                "{\"revocationVerifyingKey\":\""
                                        + TestVectors.G2_GENERATOR
                                        + "\",\"extra\":1}",
                                "publicKeys"));
        assertThrows(
                VdrException.class,
                () ->
                        validationService.requireRevocationPublicKeys(
                                "{\"revocationVerifyingKey\":\"not-a-point\"}", "publicKeys"));
        assertThrows(
                VdrException.class,
                () ->
                        validationService.requireRevocationPublicKeys(
                                "{\"revocationVerifyingKey\":42}", "publicKeys"));
    }
}
