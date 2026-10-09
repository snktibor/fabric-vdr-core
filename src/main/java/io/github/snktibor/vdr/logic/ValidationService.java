package io.github.snktibor.vdr.logic;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.snktibor.vdr.exception.VdrException;
import io.github.snktibor.vdr.model.DidDocument;
import io.github.snktibor.vdr.util.JsonUtil;
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

    // DID syntax per DID Core 1.0 section 3.1: did = "did:" method-name ":" method-specific-id,
    // where method-name is lowercase alphanumeric and method-specific-id is colon-separated groups
    // of idchars (ALPHA / DIGIT / "." / "-" / "_" / pct-encoded) ending in a non-empty group. The
    // grammar is method-agnostic on purpose — did:vdr, did:web and did:key issuers are all legal
    // here. Crucially a DID contains no "/", which is what lets an object identifier split
    // unambiguously into issuer DID and AnonCreds path at its first slash.
    private static final String DID_ID_CHAR = "(?:[A-Za-z0-9._-]|%[0-9A-Fa-f]{2})";
    private static final java.util.regex.Pattern DID_PATTERN =
            java.util.regex.Pattern.compile(
                    "^did:[a-z0-9]+:(?:" + DID_ID_CHAR + "*:)*" + DID_ID_CHAR + "+$");
    // Path segments every AnonCreds object identifier carries between the issuer DID and the object
    // type. "anoncreds" is the object-family marker the registered methods share; the version
    // segment versions the AnonCreds OBJECT SHAPES, not this registry's method specification — the
    // registered methods sit at v0 (legacy Indy-era shapes) while this registry publishes v2 ones.
    private static final String ANONCREDS_PATH = "/anoncreds/v2/";

    // Compressed BLS12-381 points in ZCash encoding, as lowercase hex: G1 = 48 bytes / 96 chars,
    // G2 = 96 bytes / 192 chars. Lowercase-only keeps ledger JSON byte-stable; base64, 0x-prefixed,
    // uppercase, and uncompressed forms all fail these patterns.
    private static final java.util.regex.Pattern G1_COMPRESSED_HEX =
            java.util.regex.Pattern.compile("^[0-9a-f]{96}$");
    private static final java.util.regex.Pattern G2_COMPRESSED_HEX =
            java.util.regex.Pattern.compile("^[0-9a-f]{192}$");
    // ZCash flag bits carried in the most significant byte of the encoding.
    private static final int FLAG_COMPRESSED = 0x80;
    private static final int FLAG_INFINITY = 0x40;
    private static final int HEX_RADIX = 16;

    /** Required (and only permitted) field of the revocation registry definition public keys. */
    static final String REVOCATION_VERIFYING_KEY_FIELD = "revocationVerifyingKey";

    /**
     * Upper bound on the guards one guarded update may carry.
     *
     * <p>Every guard costs one world-state read and one read-set key, so the bound caps both the
     * endorsement cost and the read set a single transaction commits against. A protocol run weighs
     * the alternative branches of one decision point, which is a single-digit quantity; the bound
     * is inclusive.
     */
    static final int MAX_GUARDS = 16;

    /** Required (and only permitted) fields of a guard element. */
    static final String GUARD_ID_FIELD = "revRegDefId";

    /** Version field of a guard element; see {@link #GUARD_ID_FIELD}. */
    static final String GUARD_VERSION_FIELD = "expectedVersion";

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
     * Validates that the input is a syntactically well-formed Decentralized Identifier.
     *
     * <p>Only the {@code did:<method>:<method-specific-id>} syntax of DID Core is enforced; no DID
     * method is privileged and no resolution is attempted here. Callers that need the DID to exist
     * and be active on this ledger resolve it through the DID registry afterwards.
     *
     * @param value the raw input identifier to validate
     * @param fieldName the logical name of the field
     * @return the trimmed, non-blank, and syntactically valid DID
     * @throws VdrException if the input is null, empty, whitespace only, or not a well-formed DID
     */
    public String requireValidDid(final String value, final String fieldName) {
        final String normalized = requireNotBlank(value, fieldName);

        if (!DID_PATTERN.matcher(normalized).matches()) {
            throw VdrException.invalid(
                    fieldName
                            + " format is invalid. Must match 'did:<method>:<method-specific-id>'");
        }

        return normalized;
    }

    /**
     * Validates that the input is a well-formed AnonCreds object identifier: a DID URL rooted at
     * the issuer's DID, followed by the AnonCreds path, the object type, and a strict UUIDv7.
     *
     * <p>The issuer DID is read back out of the identifier by {@link #requireIssuerDidOf} rather
     * than supplied alongside it: no transaction declares an issuer separately, so the id is its
     * sole source. Because callers pair this check with an ownership check of that issuer against
     * the verified token signer, no caller can mint an identifier rooted at a DID it does not
     * control. UUIDv7 is chosen for the object segment so identifiers carry an embedded millisecond
     * Unix timestamp, allowing ledger transactions to be sorted by creation time using the id
     * alone.
     *
     * @param value the raw input identifier to validate
     * @param objectType the required object-type token (see {@link AnonCredsObjectTypes})
     * @param fieldName the logical name of the field
     * @return the trimmed, non-blank, and fully validated identifier string
     * @throws VdrException if the input is null, empty, or whitespace only, is not rooted at a
     *     well-formed issuer DID carrying the expected AnonCreds path and object type, or if the
     *     object segment is not a valid UUIDv7
     */
    public String requireValidObjectId(
            final String value, final String objectType, final String fieldName) {
        final String normalized = requireNotBlank(value, fieldName);
        final String expectedPrefix =
                requireIssuerDidOf(normalized, fieldName) + ANONCREDS_PATH + objectType + "/";

        if (!normalized.startsWith(expectedPrefix)) {
            throw VdrException.invalid(
                    fieldName + " format is invalid. Must match '" + expectedPrefix + "<UUIDv7>'");
        }

        final String uuidPart = normalized.substring(expectedPrefix.length());
        if (!UUID_V7_PATTERN.matcher(uuidPart).matches()) {
            throw VdrException.invalid(
                    fieldName + " format is invalid. Must match '" + expectedPrefix + "<UUIDv7>'");
        }

        return normalized;
    }

    /**
     * Extracts the issuer DID that an AnonCreds object identifier is rooted at.
     *
     * <p>A DID never contains {@code /} (DID Core), so an identifier splits unambiguously into
     * issuer DID and AnonCreds path at its first slash, and the part before that slash must itself
     * be a well-formed DID. Since object-creating transactions take no separate issuer argument,
     * this is the single point at which the issuer of a created object is established: an object is
     * published under the DID its own identifier names, and nothing else.
     *
     * @param value the raw object identifier to read the issuer DID out of
     * @param fieldName the logical name of the field
     * @return the well-formed issuer DID the identifier is rooted at
     * @throws VdrException if the input is null, empty, or whitespace only, carries no path
     *     segment, or is not rooted at a well-formed DID
     */
    public String requireIssuerDidOf(final String value, final String fieldName) {
        final String normalized = requireNotBlank(value, fieldName);

        final int pathStart = normalized.indexOf('/');
        if (pathStart < 0) {
            throw VdrException.invalid(
                    fieldName + " format is invalid. Must be a DID URL rooted at the issuer DID");
        }

        return requireValidDid(normalized.substring(0, pathStart), fieldName + " issuer");
    }

    /**
     * Ensures that a payload identifier matches the requested identifier.
     *
     * <p>This prevents accidental or malicious id substitution between API path inputs and embedded
     * JSON document identifiers.
     *
     * <p>The check is fail-fast: a null identifier on either side and an id mismatch are both
     * rejected immediately with deterministic validation errors. Callers normalize both ids through
     * {@link #requireNotBlank} before comparing them, so the null case is unreachable today; the
     * guard keeps a future caller from dereferencing an absent id here, which would escape the
     * contract layer's error mapping as a NullPointerException carrying no error code.
     *
     * @param expected identifier expected by request context
     * @param actual identifier provided in payload
     * @param fieldName logical field name for error reporting
     * @throws VdrException if either identifier is null or the identifiers do not match exactly
     */
    public void requireMatchingIds(
            final String expected, final String actual, final String fieldName) {
        if (expected == null || actual == null) {
            throw VdrException.invalid(fieldName + " must not be null");
        }
        if (!expected.equals(actual.trim())) {
            throw VdrException.invalid(fieldName + " must match the requested id");
        }
    }

    /**
     * Parses JSON into a typed object using configured serializer settings.
     *
     * <p>The JSON {@code null} literal deserializes to a Java {@code null} instead of failing, so
     * it is rejected here: callers of this method dereference the result immediately, and an
     * unguarded {@code null} would escape the contract layer's error mapping as a
     * NullPointerException carrying no error code. The rejection mirrors {@link #parseJsonObject},
     * which turns the same payload away on the untyped path. The shared {@link JsonUtil}
     * deserializer stays permissive on purpose: it also reads back trusted ledger state, where an
     * absent value is the registry's business rather than a caller payload defect.
     *
     * @param <T> target object type
     * @param json JSON payload to deserialize
     * @param type target class token
     * @return deserialized typed object, never {@code null}
     * @throws VdrException if JSON cannot be parsed into the target type, or is the {@code null}
     *     literal rather than an object
     */
    public <T> T parseJson(final String json, final Class<T> type) {
        final T parsed = JsonUtil.fromJson(json, type);
        if (parsed == null) {
            throw VdrException.invalid(type.getSimpleName() + " payload must be a JSON object");
        }
        return parsed;
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
     * Parses the guard list of a guarded revocation registry entry update.
     *
     * <p>The payload is read with {@link JsonUtil#readStrictTree} rather than the shared parser: a
     * client signature covers the literal guard text, so a payload that admits two readings —
     * through a repeated object key or through content trailing the array — is rejected instead of
     * silently resolved. Every remaining rule below is likewise decidable from the text alone, so
     * the whole list is settled before the transaction reads any business state.
     *
     * <p>The array must be non-empty and at most {@link #MAX_GUARDS} long. An empty list is refused
     * rather than waved through: it would perform an unconditional write under a name that promises
     * a condition. Each element must be an object carrying exactly {@value #GUARD_ID_FIELD} and
     * {@value #GUARD_VERSION_FIELD} — a missing field leaves the guard underspecified, an extra one
     * means the caller expected a check this registry does not make. The version must be an
     * integral number in {@code [0, Long.MAX_VALUE]}. No registry may be guarded twice: with equal
     * versions the repetition is redundant, with different ones it can never be satisfied, and
     * either way the text alone decides it.
     *
     * @param json raw guard array payload
     * @param fieldName logical field name for error reporting
     * @return immutable guard list in submission order, with normalized identifiers
     * @throws VdrException if the payload violates any rule above
     */
    public List<RevRegEntryGuard> parseRevRegEntryGuards(
            final String json, final String fieldName) {
        final JsonNode node = JsonUtil.readStrictTree(json);
        if (!node.isArray()) {
            throw VdrException.invalid(fieldName + " must be a JSON array");
        }
        if (node.isEmpty()) {
            throw VdrException.invalid(fieldName + " must not be empty");
        }
        if (node.size() > MAX_GUARDS) {
            throw VdrException.invalid(
                    fieldName + " must not contain more than " + MAX_GUARDS + " entries");
        }

        final List<RevRegEntryGuard> guards = new ArrayList<>();
        final Set<String> guardedIds = new LinkedHashSet<>();
        final Iterator<JsonNode> elements = node.elements();
        while (elements.hasNext()) {
            final RevRegEntryGuard guard = parseRevRegEntryGuard(elements.next(), fieldName);
            if (!guardedIds.add(guard.getRevRegDefId())) {
                throw VdrException.invalid(
                        fieldName
                                + " must not guard the same registry twice: '"
                                + guard.getRevRegDefId()
                                + "'");
            }
            guards.add(guard);
        }
        return List.copyOf(guards);
    }

    /**
     * Parses and validates a single guard element.
     *
     * @param element array element expected to be a guard object
     * @param fieldName logical field name for error reporting
     * @return guard carrying the normalized identifier and the expected version
     * @throws VdrException if the element is not an object with exactly the two required fields, or
     *     if either field value is of the wrong type or out of range
     */
    private RevRegEntryGuard parseRevRegEntryGuard(final JsonNode element, final String fieldName) {
        if (!element.isObject()) {
            throw VdrException.invalid(fieldName + " must contain JSON objects");
        }
        if (element.size() != 2
                || !element.has(GUARD_ID_FIELD)
                || !element.has(GUARD_VERSION_FIELD)) {
            throw VdrException.invalid(
                    fieldName
                            + " element must contain exactly the fields '"
                            + GUARD_ID_FIELD
                            + "' and '"
                            + GUARD_VERSION_FIELD
                            + "'");
        }

        final JsonNode idNode = element.get(GUARD_ID_FIELD);
        if (!idNode.isTextual()) {
            throw VdrException.invalid(fieldName + "." + GUARD_ID_FIELD + " must be a string");
        }
        final String revRegDefId =
                requireNotBlank(idNode.asText(), fieldName + "." + GUARD_ID_FIELD);

        final JsonNode versionNode = element.get(GUARD_VERSION_FIELD);
        if (!versionNode.isIntegralNumber()
                || !versionNode.canConvertToLong()
                || versionNode.longValue() < 0L) {
            throw VdrException.invalid(
                    fieldName
                            + "."
                            + GUARD_VERSION_FIELD
                            + " must be an integer between 0 and "
                            + Long.MAX_VALUE);
        }

        return new RevRegEntryGuard(revRegDefId, versionNode.longValue());
    }

    /**
     * Requires a {@code type} value to be one of the allowlisted identifiers.
     *
     * <p>An allowlist (never a blocklist) keeps retired or foreign scheme names — e.g. AnonCreds v1
     * constructs like {@code CL_ACCUM} — illegal by construction. See {@link VdrTypes} for where
     * the legal identifiers come from.
     *
     * @param value raw type value
     * @param allowedTypes legal identifiers for the entity being validated
     * @param fieldName logical field name for error reporting
     * @return trimmed, allowlisted type value
     * @throws VdrException if the value is blank or not allowlisted
     */
    public String requireAllowedType(
            final String value, final Set<String> allowedTypes, final String fieldName) {
        final String normalized = requireNotBlank(value, fieldName);
        if (!allowedTypes.contains(normalized)) {
            throw VdrException.invalid(
                    fieldName
                            + " must be one of "
                            + new java.util.TreeSet<>(allowedTypes)
                            + " but was '"
                            + normalized
                            + "'");
        }
        return normalized;
    }

    /**
     * Requires a compressed BLS12-381 G1 point in canonical wire form: exactly 96 lowercase hex
     * characters encoding the 48-byte ZCash compressed representation.
     *
     * <p>Validation is FORMAT-ONLY: length, charset, and flag-bit sanity (the compression bit must
     * be set; the point-at-infinity/identity is rejected outright, which also covers the rule that
     * an infinity encoding must be all zeroes). On-curve and subgroup membership are deliberately
     * not checked — that requires a pairing library the chaincode does not carry, and the AnonCreds
     * v2 reference deserializer enforces membership client-side on every read.
     *
     * @param value raw accumulator value
     * @param fieldName logical field name for error reporting
     * @return validated lowercase hex value
     * @throws VdrException if the value is not a canonical compressed non-identity G1 encoding
     */
    public String requireCompressedG1Hex(final String value, final String fieldName) {
        return requireCompressedPointHex(value, G1_COMPRESSED_HEX, "G1", 96, fieldName);
    }

    /**
     * Requires a compressed BLS12-381 G2 point in canonical wire form: exactly 192 lowercase hex
     * characters encoding the 96-byte ZCash compressed representation.
     *
     * <p>Same format-only validation scope as {@link #requireCompressedG1Hex}.
     *
     * @param value raw key value
     * @param fieldName logical field name for error reporting
     * @return validated lowercase hex value
     * @throws VdrException if the value is not a canonical compressed non-identity G2 encoding
     */
    public String requireCompressedG2Hex(final String value, final String fieldName) {
        return requireCompressedPointHex(value, G2_COMPRESSED_HEX, "G2", 192, fieldName);
    }

    /**
     * Parses and validates the revocation registry definition public keys object.
     *
     * <p>The object must contain exactly one field, {@value #REVOCATION_VERIFYING_KEY_FIELD},
     * holding the VB20 accumulator verifying key as a compressed BLS12-381 G2 point in canonical
     * hex form. Unknown or extra fields are rejected rather than stored, keeping the ledger payload
     * fully specified.
     *
     * @param json raw public keys JSON payload
     * @param fieldName logical field name for error reporting
     * @return parsed and validated JSON object node
     * @throws VdrException if the payload is not an object with exactly the required field or the
     *     key value is not a canonical compressed G2 encoding
     */
    public JsonNode requireRevocationPublicKeys(final String json, final String fieldName) {
        final JsonNode node = parseJsonObject(json, fieldName);
        if (node.size() != 1 || !node.has(REVOCATION_VERIFYING_KEY_FIELD)) {
            throw VdrException.invalid(
                    fieldName
                            + " must contain exactly one field '"
                            + REVOCATION_VERIFYING_KEY_FIELD
                            + "'");
        }
        final JsonNode key = node.get(REVOCATION_VERIFYING_KEY_FIELD);
        if (!key.isTextual()) {
            throw VdrException.invalid(
                    fieldName + "." + REVOCATION_VERIFYING_KEY_FIELD + " must be a string");
        }
        requireCompressedG2Hex(key.asText(), fieldName + "." + REVOCATION_VERIFYING_KEY_FIELD);
        return node;
    }

    /**
     * Shared format check for compressed BLS12-381 point encodings.
     *
     * @param value raw input value
     * @param pattern lowercase hex pattern of the expected length
     * @param groupName group label for error messages (G1 or G2)
     * @param hexLength expected number of hex characters
     * @param fieldName logical field name for error reporting
     * @return validated value
     * @throws VdrException on any format violation
     */
    private String requireCompressedPointHex(
            final String value,
            final java.util.regex.Pattern pattern,
            final String groupName,
            final int hexLength,
            final String fieldName) {
        final String normalized = requireNotBlank(value, fieldName);
        if (!pattern.matcher(normalized).matches()) {
            throw VdrException.invalid(
                    fieldName
                            + " must be a compressed BLS12-381 "
                            + groupName
                            + " point as exactly "
                            + hexLength
                            + " lowercase hex characters");
        }
        final int flagByte = Integer.parseInt(normalized.substring(0, 2), HEX_RADIX);
        if ((flagByte & FLAG_COMPRESSED) == 0) {
            throw VdrException.invalid(
                    fieldName + " must use the compressed " + groupName + " encoding");
        }
        if ((flagByte & FLAG_INFINITY) != 0) {
            throw VdrException.invalid(
                    fieldName + " must not be the " + groupName + " identity element");
        }
        return normalized;
    }

    /**
     * Validates object-shape constraints for embedded DID document arrays.
     *
     * <p>A DID document must carry at least one verification method, and every entry must be a JSON
     * object with non-empty textual {@code id}, {@code type} and {@code controller}, typed {@code
     * JsonWebKey2020}, and a {@code publicKeyJwk} object holding an EC P-256 key with non-empty
     * textual {@code kty}, {@code crv}, {@code x} and {@code y} — the shape the ES256 JWT
     * authorization path resolves signing keys from. Service entries are optional, but must be JSON
     * objects when provided.
     *
     * @param document DID document instance to validate
     * @throws VdrException if the verification method array is missing or empty, any verification
     *     method violates the shape above, or any service entry is null or non-object
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
