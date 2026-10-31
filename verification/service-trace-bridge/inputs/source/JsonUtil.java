package com.example.vdr.util;

import com.example.vdr.exception.VdrException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Shared JSON utility for deterministic VDR processing.
 *
 * <p>This utility centralizes JSON parsing/serialization behavior, canonical output configuration,
 * and uniform exception mapping to {@link VdrException} so services can rely on deterministic
 * serialization and predictable error handling.
 */
public final class JsonUtil {

    /**
     * Canonically configured mapper used across the chaincode.
     *
     * <p>Property and map key ordering are enabled to guarantee deterministic JSON byte streams
     * across all peers executing a transaction.
     */
    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    /**
     * Mapper for payloads whose exact text is covered by a client signature.
     *
     * <p>It differs from {@link #MAPPER} in exactly two leniencies, turned off here and nowhere
     * else. Duplicate object keys, which Jackson otherwise resolves silently to the last
     * occurrence, are a parse error; so is any content after the first complete JSON value, which
     * Jackson otherwise stops before and ignores. Either leniency lets one signed byte string be
     * read two ways, so signer, chaincode and a later auditor could each take a different meaning
     * from the same audited text. The shared mapper deliberately keeps its behaviour: it also reads
     * back trusted ledger state, where neither condition can arise from a caller.
     */
    private static final ObjectMapper STRICT_MAPPER =
            MAPPER.copy()
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    /** Utility class constructor is private to prevent instantiation. */
    private JsonUtil() {}

    /**
     * Deserializes JSON into a typed object using canonical mapper settings.
     *
     * @param <T> target type
     * @param json JSON payload to deserialize
     * @param type target class token
     * @return deserialized typed object
     * @throws VdrException if JSON parsing fails for the requested type
     */
    public static <T> T fromJson(final String json, final Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (final Exception e) {
            throw VdrException.invalid(
                    "Invalid JSON for " + type.getSimpleName() + ": " + safeMessage(e));
        }
    }

    /**
     * Parses JSON into a tree representation.
     *
     * @param json JSON payload to parse
     * @return parsed JSON node tree
     * @throws VdrException if JSON payload is invalid
     */
    public static JsonNode readTree(final String json) {
        try {
            return MAPPER.readTree(json);
        } catch (final Exception e) {
            throw VdrException.invalid("Invalid JSON payload: " + safeMessage(e));
        }
    }

    /**
     * Parses JSON into a tree, requiring the payload to have exactly one reading.
     *
     * <p>Unlike {@link #readTree} this rejects duplicate keys inside an object and any content
     * after the first complete JSON value. Reserved for caller payloads that are bound by a
     * signature over their literal text: there, a payload that parses two ways is a payload two
     * parties can disagree about while both quoting the same signed bytes.
     *
     * @param json JSON payload to parse
     * @return parsed JSON node tree
     * @throws VdrException if the payload is invalid, repeats an object key, or carries trailing
     *     content
     */
    public static JsonNode readStrictTree(final String json) {
        try {
            return STRICT_MAPPER.readTree(json);
        } catch (final Exception e) {
            throw VdrException.invalid("Invalid JSON payload: " + safeMessage(e));
        }
    }

    /**
     * Serializes a value as canonical JSON with deterministic ordering.
     *
     * <p>Deterministic output is required for reproducible hash computation across peers and
     * clients.
     *
     * @param value value to serialize
     * @return canonical JSON string
     * @throws VdrException if serialization fails
     */
    public static String toCanonicalJson(final Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (final JsonProcessingException e) {
            throw VdrException.invalid("Unable to serialize JSON: " + safeMessage(e));
        }
    }

    /**
     * Creates an empty Jackson array node using the shared mapper.
     *
     * @return new mutable array node
     */
    public static ArrayNode arrayNode() {
        return MAPPER.createArrayNode();
    }

    /**
     * Creates an empty Jackson object node using the shared mapper.
     *
     * @return new mutable object node
     */
    public static ObjectNode objectNode() {
        return MAPPER.createObjectNode();
    }

    /**
     * Trims a string and converts blank results to {@code null}.
     *
     * @param value raw input value
     * @return trimmed value or {@code null} when input is null/blank
     */
    public static String trimToNull(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Produces a non-empty exception message for user-facing error contexts.
     *
     * <p>When an exception carries no message the simple class name stands in, so a failure
     * response stays diagnosable without ever exposing an empty reason.
     *
     * @param exception source exception
     * @return exception message or exception simple type name when blank
     */
    public static String safeMessage(final Exception exception) {
        final String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
