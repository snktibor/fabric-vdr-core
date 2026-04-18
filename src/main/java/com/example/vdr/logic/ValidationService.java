package com.example.vdr.logic;

import com.example.vdr.exception.VdrException;
import com.example.vdr.model.DidDocument;
import com.example.vdr.model.SchemaRecord;
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
        if (document.getVerificationMethod() != null) {
            for (final JsonNode method : document.getVerificationMethod()) {
                if (method == null || !method.isObject()) {
                    throw VdrException.invalid("verificationMethod must contain JSON objects");
                }
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
     * Validates that credential JSON fields are declared by the schema.
     *
     * <p>This guards against undeclared claims and keeps credential payloads constrained to the
     * issuer-defined attribute model.
     *
     * @param credentialJson credential payload to validate
     * @param schemaRecord schema definition that supplies allowed attribute names
     * @throws VdrException if payload is not an object or includes undeclared fields
     */
    public void validateCredentialAgainstSchema(
            final JsonNode credentialJson, final SchemaRecord schemaRecord) {
        if (!credentialJson.isObject()) {
            throw VdrException.invalid("credential must be a JSON object");
        }

        final java.util.Set<String> schemaAttrs =
                new java.util.HashSet<>(schemaRecord.getAttrNames());
        final java.util.Iterator<String> fieldNames = credentialJson.fieldNames();

        while (fieldNames.hasNext()) {
            final String fieldName = fieldNames.next();
            if (!schemaAttrs.contains(fieldName)) {
                throw VdrException.invalid(
                        "credential field '" + fieldName + "' not defined in schema");
            }
        }
    }
}
