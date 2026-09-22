package com.example.vdr.e2e.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * The guard list of one guarded update, held as the single JSON text that goes on the wire.
 *
 * <p>The transaction digest covers this argument as the literal string the client sends, so the
 * token and the invocation have to be handed the same characters. Rendering the list twice — even
 * from the same values — may reorder keys or change spacing, and every guarded transaction would
 * then be refused for a scope mismatch instead of on what it actually asked for. Holding the
 * rendering rather than the values makes that mistake unavailable: the text exists once.
 *
 * <p>The rendering is the harness's own, not the chaincode's canonicaliser. The guard argument has
 * no canonical form — the digest binds whatever the client sent — so a client that happens to
 * serialise the way the chaincode would is proving less than one that does not.
 */
public final class GuardsJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String text;

    private GuardsJson(final String text) {
        this.text = text;
    }

    /** Collects guards in submission order; the response reports them in the same order. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Takes a payload verbatim, for shapes a serialiser will not produce — a duplicated JSON key,
     * for one, which most writers silently collapse to the last value.
     */
    public static GuardsJson raw(final String text) {
        return new GuardsJson(text);
    }

    /** The one rendering; both the token and the invocation are given this string. */
    public String text() {
        return text;
    }

    @Override
    public String toString() {
        return text;
    }

    /** Accumulates guard entries and renders them exactly once. */
    public static final class Builder {

        private final List<String> revRegDefIds = new ArrayList<>();
        private final List<Long> expectedVersions = new ArrayList<>();

        private Builder() {}

        /**
         * Adds one guard.
         *
         * @param revRegDefId identifier as it is submitted — already resolved, never a handle
         * @param expectedVersion version the caller observed the guarded registry at
         * @return this builder
         */
        public Builder guard(final String revRegDefId, final long expectedVersion) {
            revRegDefIds.add(revRegDefId);
            expectedVersions.add(expectedVersion);
            return this;
        }

        /** Renders the collected guards; may be called once per list that is submitted. */
        public GuardsJson build() {
            final ArrayNode array = MAPPER.createArrayNode();
            for (int i = 0; i < revRegDefIds.size(); i++) {
                final ObjectNode guard = MAPPER.createObjectNode();
                guard.put("revRegDefId", revRegDefIds.get(i));
                guard.put("expectedVersion", expectedVersions.get(i));
                array.add(guard);
            }
            try {
                return new GuardsJson(MAPPER.writeValueAsString(array));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Could not render the guard list.", e);
            }
        }
    }
}
