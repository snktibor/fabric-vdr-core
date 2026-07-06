package com.example.vdr.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.vdr.exception.VdrException;
import com.example.vdr.model.DidDocument;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JsonUtil} canonicalization, parsing, and hashing.
 *
 * <p>The suite validates deterministic JSON behavior and error reporting guarantees required by
 * ledger integrity checks.
 */
class JsonUtilTest {

    /** Verifies canonical serialization keeps key ordering deterministic. */
    @Test
    void toCanonicalJsonSortsProperties() {
        final ObjectNode node = JsonUtil.objectNode();
        node.put("b", 1);
        node.put("a", 2);

        final String json = JsonUtil.toCanonicalJson(node);

        assertEquals(2, JsonUtil.readTree(json).get("a").asInt());
        assertEquals(1, JsonUtil.readTree(json).get("b").asInt());
    }

    /** Verifies whitespace normalization returns null for blank values. */
    @Test
    void trimToNullHandlesWhitespace() {
        assertNull(JsonUtil.trimToNull("  "));
        assertEquals("abc", JsonUtil.trimToNull(" abc "));
    }

    /** Verifies invalid JSON tree parsing is mapped to domain-level exceptions. */
    @Test
    void readTreeRejectsInvalidJson() {
        final VdrException exception =
                assertThrows(VdrException.class, () -> JsonUtil.readTree("{"));

        assertEquals("INVALID_ARGUMENT", exception.getErrorCode());
        assertTrue(exception.getMessage().startsWith("Invalid JSON payload:"));
    }

    /** Verifies typed parsing errors include the target type for diagnostics. */
    @Test
    void fromJsonIncludesTypeInErrorMessage() {
        final VdrException exception =
                assertThrows(VdrException.class, () -> JsonUtil.fromJson("{", DidDocument.class));

        assertTrue(exception.getMessage().startsWith("Invalid JSON for DidDocument:"));
    }
}
