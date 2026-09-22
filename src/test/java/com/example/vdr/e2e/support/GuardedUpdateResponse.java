package com.example.vdr.e2e.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What a guarded update answers with: the entry as it stands after the write, and the version each
 * named guard was read at inside the transaction.
 *
 * <p>The two numbers are different facts under deliberately different names. {@code entry.version}
 * is where the target moved to; {@code guards[i].checkedVersion} is where a guarded registry was
 * found. A caller continuing with a further update needs the first for a guard on the target and
 * the second for every other registry, so a reader that blurred them would hide exactly the mistake
 * the chained scenarios exist to catch. Guards are looked up by identifier rather than by position,
 * so a response that reordered them would fail on the version, not on the index.
 */
public final class GuardedUpdateResponse {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String raw;
    private final JsonNode root;

    private GuardedUpdateResponse(final String raw, final JsonNode root) {
        this.raw = raw;
        this.root = root;
    }

    /** Parses a submit response payload. */
    public static GuardedUpdateResponse parse(final String json) {
        if (json == null) {
            throw new IllegalStateException(
                    "No guarded update response to read; the submission did not commit.");
        }
        try {
            return new GuardedUpdateResponse(json, MAPPER.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Guarded update response was not readable as JSON: " + json, e);
        }
    }

    /** The version the updated entry stands at after the write. */
    public long entryVersion() {
        final JsonNode version = root.path("entry").path("version");
        if (!version.isIntegralNumber()) {
            throw new IllegalStateException(
                    "Guarded update response carries no entry.version: " + raw);
        }
        return version.longValue();
    }

    /** The version the guard naming {@code revRegDefId} was read at inside the transaction. */
    public long checkedVersion(final String revRegDefId) {
        for (final JsonNode guard : root.path("guards")) {
            if (revRegDefId.equals(guard.path("revRegDefId").asText(null))) {
                final JsonNode checked = guard.path("checkedVersion");
                if (!checked.isIntegralNumber()) {
                    throw new IllegalStateException(
                            "Guard '" + revRegDefId + "' carries no checkedVersion: " + raw);
                }
                return checked.longValue();
            }
        }
        throw new IllegalStateException(
                "No guard named '" + revRegDefId + "' in the response: " + raw);
    }

    /** The response as it came off the wire, for assertion messages. */
    public String raw() {
        return raw;
    }
}
