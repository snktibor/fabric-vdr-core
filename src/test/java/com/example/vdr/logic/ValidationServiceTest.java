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

    /** Verifies a prefixed UUIDv7 identifier is accepted and returned trimmed. */
    @Test
    void requireValidEntityIdAcceptsPrefixedUuidV7() {
        assertEquals(
                "schema:vdr:018f6c02-3a5e-7cbb-9d3e-5f2a4b6c8d0e",
                validationService.requireValidEntityId(
                        " schema:vdr:018f6c02-3a5e-7cbb-9d3e-5f2a4b6c8d0e ", "schema:vdr:", "id"));
    }

    /** Verifies an id with the wrong prefix is rejected as a validation failure. */
    @Test
    void requireValidEntityIdRejectsWrongPrefix() {
        assertThrows(
                VdrException.class,
                () ->
                        validationService.requireValidEntityId(
                                "creddef:vdr:018f6c02-3a5e-7cbb-9d3e-5f2a4b6c8d0e",
                                "schema:vdr:",
                                "id"));
    }

    /** Verifies an id whose suffix is not a UUIDv7 is rejected as a validation failure. */
    @Test
    void requireValidEntityIdRejectsNonUuidV7() {
        assertThrows(
                VdrException.class,
                () ->
                        validationService.requireValidEntityId(
                                "schema:vdr:not-a-uuid-v7", "schema:vdr:", "id"));
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
