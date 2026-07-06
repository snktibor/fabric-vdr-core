package com.example.vdr.logic;

import com.example.vdr.exception.VdrException;
import com.example.vdr.model.DidDocument;
import com.example.vdr.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Layer-3 validation utility for fail-fast input and payload checks.
 *
 * <p>Business services use this component to keep validation deterministic, centralized, and
 * reusable across entity workflows before any state mutation is attempted.
 */
public class ValidationService {

    private static final java.util.regex.Pattern UUID_V7_PATTERN =
            java.util.regex.Pattern.compile(
                    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-7[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");

    /**
     * Normalizes a text field and rejects blank or null values.
     *
     * @param value raw input value
     * @param fieldName logical field name for error reporting
     * @return trimmed non-blank value
     * @throws VdrException if the input is null, empty, or whitespace only
     */
    public String requireNotBlank(final String value, final String fieldName) {
        final String normalized = JsonUtil.trimToNull(value);
        if (normalized == null) {
            throw VdrException.invalid(fieldName + " must not be blank");
        }
        return normalized;
    }

    /**
     * Validates that the input is a well-formed Uniform Resource Name (URN) consisting of a
     * specific prefix followed by a strict UUIDv7.
     *
     * <p>This method first normalizes the input by rejecting blank or null values. It is designed
     * to be highly reusable for any current or future ledger entities (e.g., {@code "did:vdr:"},
     * {@code "schema:vdr:"}, {@code "creddef:vdr:"}). UUIDv7 is chosen so identifiers carry an
     * embedded millisecond Unix timestamp, allowing ledger transactions to be sorted by creation
     * time using the id alone.
     *
     * @param value the raw input identifier to validate
     * @param expectedPrefix the required namespace prefix (e.g., {@code "did:vdr:"})
     * @param fieldName the logical name of the field
     * @return the trimmed, non-blank, and fully validated identifier string
     * @throws VdrException if the input is null, empty, or whitespace only
     * @throws IllegalArgumentException if the input does not start with the expected prefix, or if
     *     the suffix is not a valid UUIDv7
     */
    public String requireValidEntityId(
            final String value, final String expectedPrefix, final String fieldName) {
        String normalized = requireNotBlank(value, fieldName);

        if (!normalized.startsWith(expectedPrefix)) {
            throw new IllegalArgumentException(
                    fieldName
                            + " format is invalid. Must start with prefix '"
                            + expectedPrefix
                            + "'");
        }

        String uuidPart = normalized.substring(expectedPrefix.length());
        if (!UUID_V7_PATTERN.matcher(uuidPart).matches()) {
            throw new IllegalArgumentException(
                    fieldName
                            + " format is invalid. Must contain a valid UUIDv7 after the prefix.");
        }

        return normalized;
    }

    /**
     * Ensures that a payload identifier matches the requested identifier.
     *
     * <p>This prevents accidental or malicious id substitution between API path inputs and embedded
     * JSON document identifiers.
     *
     * @param expected identifier expected by request context
     * @param actual identifier provided in payload
     * @param fieldName logical field name for error reporting
     * @throws VdrException if identifiers do not match exactly
     */
    public void requireMatchingIds(
            final String expected, final String actual, final String fieldName) {
        if (!expected.equals(actual.trim())) {
            throw VdrException.invalid(fieldName + " must match the requested id");
        }
    }

    /**
     * Parses JSON into a typed object using configured serializer settings.
     *
     * @param <T> target object type
     * @param json JSON payload to deserialize
     * @param type target class token
     * @return deserialized typed object
     * @throws VdrException if JSON cannot be parsed into the target type
     */
    public <T> T parseJson(final String json, final Class<T> type) {
        return JsonUtil.fromJson(json, type);
    }

    /**
     * Parses JSON and enforces object shape.
     *
     * @param json JSON payload expected to be an object
     * @param fieldName logical field name for error reporting
     * @return parsed JSON object node
     * @throws VdrException if JSON is invalid or not an object
     */
    public JsonNode parseJsonObject(final String json, final String fieldName) {
        final JsonNode node = JsonUtil.readTree(json);
        if (!node.isObject()) {
            throw VdrException.invalid(fieldName + " must be a JSON object");
        }
        return node;
    }

    /**
     * Parses a JSON array of unique, non-empty strings.
     *
     * <p>The method preserves input order while enforcing non-empty semantics and duplicate
     * prevention to keep schema attributes deterministic.
     *
     * @param json JSON payload expected to be an array of strings
     * @param fieldName logical field name for error reporting
     * @return immutable list of normalized string values
     * @throws VdrException if payload is not a valid non-empty unique string array
     */
    public List<String> parseStringArray(final String json, final String fieldName) {
        final JsonNode node = JsonUtil.readTree(json);
        if (!node.isArray()) {
            throw VdrException.invalid(fieldName + " must be a JSON array");
        }

        final List<String> values = new ArrayList<>();
        final Iterator<JsonNode> elements = node.elements();
        while (elements.hasNext()) {
            final JsonNode element = elements.next();
            if (!element.isTextual()) {
                throw VdrException.invalid(fieldName + " must contain only strings");
            }
            final String value = requireNotBlank(element.asText(), fieldName + " element");
            values.add(value);
        }

        if (values.isEmpty()) {
            throw VdrException.invalid(fieldName + " must not be empty");
        }

        final Set<String> unique = new LinkedHashSet<>(values);
        if (unique.size() != values.size()) {
            throw VdrException.invalid(fieldName + " must not contain duplicate values");
        }

        return List.copyOf(values);
    }

    /**
     * Validates object-shape constraints for embedded DID document arrays.
     *
     * <p>Verification method and service entries must be JSON objects when provided, ensuring
     * predictable downstream processing.
     *
     * @param document DID document instance to validate
     * @throws VdrException if any embedded array entry is null or non-object
     */
    public void validateDidDocumentShape(final DidDocument document) {
        if (document.getVerificationMethod() == null
                || document.getVerificationMethod().isEmpty()) {
            throw VdrException.invalid("verificationMethod must contain at least one entry");
        }

        for (final JsonNode method : document.getVerificationMethod()) {
            if (method == null || !method.isObject()) {
                throw VdrException.invalid("verificationMethod must contain JSON objects");
            }

            requireJsonTextField(method, "id", "verificationMethod");
            requireJsonTextField(method, "type", "verificationMethod");
            requireJsonTextField(method, "controller", "verificationMethod");

            if (!"JsonWebKey2020".equals(method.get("type").asText())) {
                throw VdrException.invalid(
                        "verificationMethod type must be JsonWebKey2020 for jwt authorization");
            }

            final JsonNode jwk = method.get("publicKeyJwk");
            if (jwk == null || !jwk.isObject()) {
                throw VdrException.invalid("verificationMethod must include publicKeyJwk object");
            }

            requireJsonTextField(jwk, "kty", "publicKeyJwk");
            requireJsonTextField(jwk, "crv", "publicKeyJwk");
            requireJsonTextField(jwk, "x", "publicKeyJwk");
            requireJsonTextField(jwk, "y", "publicKeyJwk");

            if (!"EC".equals(jwk.get("kty").asText()) || !"P-256".equals(jwk.get("crv").asText())) {
                throw VdrException.invalid(
                        "publicKeyJwk must use EC key type with P-256 curve for ES256");
            }
        }

        if (document.getService() != null) {
            for (final JsonNode serviceEntry : document.getService()) {
                if (serviceEntry == null || !serviceEntry.isObject()) {
                    throw VdrException.invalid("service must contain JSON objects");
                }
            }
        }
    }

    /**
     * Requires a JSON text field to be present and non-empty.
     *
     * @param node the JSON node to check
     * @param fieldName the name of the field to check
     * @param sectionName the name of the section containing the field
     * @throws VdrException if the field is missing, not textual, or empty
     */
    private void requireJsonTextField(
            final JsonNode node, final String fieldName, final String sectionName) {
        final JsonNode value = node.get(fieldName);
        if (value == null || !value.isTextual() || JsonUtil.trimToNull(value.asText()) == null) {
            throw VdrException.invalid(
                    sectionName + " must include non-empty textual field '" + fieldName + "'");
        }
    }
}
