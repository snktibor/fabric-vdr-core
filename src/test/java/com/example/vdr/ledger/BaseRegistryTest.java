package com.example.vdr.ledger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.vdr.model.DidDocument;
import com.example.vdr.util.JsonUtil;
import java.util.List;
import org.hyperledger.fabric.shim.ChaincodeStub;
import org.hyperledger.fabric.shim.ledger.CompositeKey;
import org.hyperledger.fabric.shim.ledger.KeyValue;
import org.hyperledger.fabric.shim.ledger.QueryResultsIterator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link BaseRegistry} core persistence and query behavior.
 *
 * <p>The suite validates existence checks, JSON round-tripping, list projections, composite-key
 * handling, and safe resource cleanup behavior.
 */
@SuppressWarnings("java:S1192")
@ExtendWith(MockitoExtension.class)
class BaseRegistryTest {

    /** Test adapter exposing selected protected helpers for direct verification. */
    private static class TestRegistry extends BaseRegistry<DidDocument> {
        /**
         * Creates a test registry bound to DID namespace.
         *
         * @param stub mocked chaincode stub backend
         */
        TestRegistry(final ChaincodeStub stub) {
            super(stub, LedgerNamespaces.DID, DidDocument.class);
        }

        /**
         * Exposes composite-key construction helper for assertions.
         *
         * @param id business identifier
         * @return full composite ledger key
         */
        String exposeCompositeKey(final String id) {
            return compositeKey(id);
        }

        /**
         * Exposes close helper to verify failure suppression behavior.
         *
         * @param closeable resource to close quietly
         */
        void exposeCloseQuietly(final AutoCloseable closeable) {
            closeQuietly(closeable);
        }
    }

    @Mock private ChaincodeStub stub;

    private TestRegistry registry;

    /** Creates registry under test before each method. */
    @BeforeEach
    void setUp() {
        registry = new TestRegistry(stub);
    }

    /** Configures mock behavior for deterministic composite-key generation. */
    private void stubCompositeKey() {
        when(stub.createCompositeKey(eq(LedgerNamespaces.DID), anyString()))
                .thenAnswer(
                        invocation -> {
                            final Object attributes = invocation.getArgument(1);
                            final List<String> values;
                            if (attributes instanceof String[]) {
                                values = List.of((String[]) attributes);
                            } else if (attributes instanceof List) {
                                values = List.copyOf((List<String>) attributes);
                            } else {
                                values = List.of(String.valueOf(attributes));
                            }
                            return new CompositeKey(invocation.getArgument(0), values);
                        });
    }

    /** Verifies blank state entries are treated as non-existent. */
    @Test
    void existsReturnsFalseForBlankState() {
        stubCompositeKey();
        final String key = registry.exposeCompositeKey("id");
        when(stub.getStringState(key)).thenReturn(" ");

        assertFalse(registry.exists("id"));
    }

    /** Verifies non-empty state entries are treated as existing. */
    @Test
    void existsReturnsTrueForPopulatedState() {
        stubCompositeKey();
        final String key = registry.exposeCompositeKey("id");
        when(stub.getStringState(key)).thenReturn("{\"id\":\"did:1\"}");

        assertTrue(registry.exists("id"));
    }

    /** Verifies get operation returns null when no value is stored. */
    @Test
    void getReturnsNullWhenMissing() {
        stubCompositeKey();
        final String key = registry.exposeCompositeKey("id");
        when(stub.getStringState(key)).thenReturn("");

        assertNull(registry.get("id"));
    }

    /** Verifies stored JSON values are deserialized into typed records. */
    @Test
    void getParsesJson() {
        stubCompositeKey();
        final DidDocument document = new DidDocument();
        document.setId("did:1");
        final String json = JsonUtil.toCanonicalJson(document);
        final String key = registry.exposeCompositeKey("did:1");
        when(stub.getStringState(key)).thenReturn(json);

        final DidDocument stored = registry.get("did:1");

        assertEquals("did:1", stored.getId());
    }

    /** Verifies put operation persists canonical JSON representation. */
    @Test
    void putWritesCanonicalJson() {
        stubCompositeKey();
        final DidDocument document = new DidDocument();
        document.setId("did:1");
        final String key = registry.exposeCompositeKey("did:1");

        registry.put("did:1", document);

        verify(stub).putStringState(key, JsonUtil.toCanonicalJson(document));
    }

    /** Verifies list operation maps entries and closes result iterator. */
    @Test
    void listAllReturnsEntriesAndClosesIterator() {
        final DidDocument document = new DidDocument();
        document.setId("did:1");

        final KeyValue keyValue = mock(KeyValue.class);
        when(keyValue.getKey())
                .thenReturn(new CompositeKey(LedgerNamespaces.DID, List.of("did:1")).toString());
        when(keyValue.getStringValue()).thenReturn(JsonUtil.toCanonicalJson(document));

        final QueryResultsIterator<KeyValue> iterator = mock(QueryResultsIterator.class);
        when(iterator.iterator()).thenReturn(List.of(keyValue).iterator());
        when(stub.getStateByPartialCompositeKey(LedgerNamespaces.DID)).thenReturn(iterator);

        final List<RegistryEntry<DidDocument>> entries = registry.listAll();

        assertEquals(1, entries.size());
        assertEquals("did:1", entries.get(0).getId());
        assertDoesNotThrow(() -> verify(iterator).close());
    }

    /** Verifies close helper suppresses exceptions from closeable resources. */
    @Test
    void closeQuietlyIgnoresFailures() {
        final AutoCloseable closeable =
                () -> {
                    throw new java.io.IOException("boom");
                };

        assertDoesNotThrow(() -> registry.exposeCloseQuietly(closeable));
    }
}
