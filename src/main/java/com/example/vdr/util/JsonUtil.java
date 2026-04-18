package com.example.vdr.util;

import com.example.vdr.exception.VdrException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Shared JSON and hashing utility for deterministic VDR processing.
 *
 * <p>This utility centralizes JSON parsing/serialization behavior, canonical output configuration,
 * and uniform exception mapping to {@link VdrException} so services can enforce stable hashing and
 * predictable error handling.
 */
public final class JsonUtil {

    /**
     * Canonically configured mapper used across the chaincode.
     *
     * <p>Property and map key ordering are enabled to support deterministic JSON byte streams
     * required by hash-based credential integrity verification.
     */
    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

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
     * @param exception source exception
     * @return exception message or exception simple type name when blank
     */
    private static String safeMessage(final Exception exception) {
        final String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    /**
     * Computes a lowercase hexadecimal SHA-256 hash for provided content.
     *
     * @param content input content to hash
     * @return SHA-256 hash encoded as lowercase hex
     * @throws VdrException if SHA-256 algorithm is unavailable on runtime
     */
    public static String sha256(final String content) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (final NoSuchAlgorithmException e) {
            throw VdrException.invalid("SHA-256 algorithm not available: " + e.getMessage());
        }
    }

    /**
     * Encodes raw bytes into lowercase hexadecimal representation.
     *
     * @param bytes byte array to encode
     * @return lowercase hexadecimal string
     */
    private static String bytesToHex(final byte[] bytes) {
        final StringBuilder hexString = new StringBuilder();
        for (final byte b : bytes) {
            final String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
