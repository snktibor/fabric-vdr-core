package com.example.vdr.ledger;

import com.example.vdr.util.JsonUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.hyperledger.fabric.shim.ChaincodeStub;
import org.hyperledger.fabric.shim.ledger.CompositeKey;
import org.hyperledger.fabric.shim.ledger.KeyModification;
import org.hyperledger.fabric.shim.ledger.KeyValue;
import org.hyperledger.fabric.shim.ledger.QueryResultsIterator;

/**
 * Layer 4: Data Access. This class provides a generic persistence layer for Hyperledger Fabric,
 * encapsulating all direct interactions with the {@link ChaincodeStub}.
 *
 * <p>It manages the creation of Fabric Composite Keys to prevent namespace collisions and handles
 * the serialization/deserialization of domain objects to/from the ledger.
 *
 * @param <T> The type of domain object managed by this registry.
 */
public abstract class BaseRegistry<T> {

    private final ChaincodeStub stub;
    private final String objectType;
    private final Class<T> type;

    /**
     * Initializes the registry with its core Fabric dependencies.
     *
     * @param stub The Fabric ChaincodeStub.
     * @param objectType The primary component of the composite key namespace.
     * @param type The class type for deserialization.
     */
    protected BaseRegistry(final ChaincodeStub stub, final String objectType, final Class<T> type) {
        this.stub = stub;
        this.objectType = objectType;
        this.type = type;
    }

    /**
     * Checks if an entity with the given ID exists on the ledger.
     *
     * @param id The unique identifier for the entity.
     * @return {@code true} if the entity exists and is not empty.
     */
    public boolean exists(final String id) {
        final String value = stub.getStringState(compositeKey(id));
        return value != null && !value.isBlank();
    }

    /**
     * Fetches and deserializes an entity from the ledger by its ID.
     *
     * @param id The unique identifier for the entity.
     * @return The deserialized object, or {@code null} if it doesn't exist.
     */
    public T get(final String id) {
        final String value = stub.getStringState(compositeKey(id));
        if (value == null || value.isBlank()) {
            return null;
        }
        return JsonUtil.fromJson(value, type);
    }

    /**
     * Serializes a domain object and writes it to the ledger. This method uses {@link JsonUtil} to
     * ensure canonical formatting of the stored data.
     *
     * @param id The unique identifier for the entity.
     * @param value The object to be persisted.
     */
    public void put(final String id, final T value) {
        stub.putStringState(compositeKey(id), JsonUtil.toCanonicalJson(value));
    }

    /**
     * Performs a prefix scan on the ledger to retrieve all entities under this registry's composite
     * key namespace.
     *
     * @return A list of all registry entries (ID and value).
     */
    public List<RegistryEntry<T>> listAll() {
        final List<RegistryEntry<T>> results = new ArrayList<>();
        QueryResultsIterator<KeyValue> iterator = null;
        try {
            iterator = stub.getStateByPartialCompositeKey(objectType);
            for (final KeyValue entry : iterator) {
                final CompositeKey key = CompositeKey.parseCompositeKey(entry.getKey());
                final String id =
                        key.getAttributes().isEmpty() ? entry.getKey() : key.getAttributes().get(0);
                final T value = JsonUtil.fromJson(entry.getStringValue(), type);
                results.add(new RegistryEntry<>(id, value));
            }
            return List.copyOf(results);
        } finally {
            closeQuietly(iterator);
        }
    }

    /**
     * Returns the full revision history of an entity key, oldest revision first.
     *
     * <p>Backed by {@code stub.getHistoryForKey}, which requires the peer's history database
     * (enabled by default in Fabric). Fabric 2.x reports modifications newest-first; the result is
     * reversed here so callers always receive chronological (oldest-first) order. Deletion
     * tombstones are preserved as revisions with {@code deleted=true} and a {@code null} value —
     * they are never parsed as records, so a future delete cannot surface as a malformed entity. An
     * empty list means the key never existed.
     *
     * @param id The unique identifier for the entity.
     * @return revision list in chronological order, possibly empty
     */
    public List<KeyRevision<T>> history(final String id) {
        final List<KeyRevision<T>> revisions = new ArrayList<>();
        QueryResultsIterator<KeyModification> iterator = null;
        try {
            iterator = stub.getHistoryForKey(compositeKey(id));
            for (final KeyModification modification : iterator) {
                final T value =
                        modification.isDeleted()
                                ? null
                                : JsonUtil.fromJson(modification.getStringValue(), type);
                revisions.add(
                        new KeyRevision<>(
                                modification.getTxId(),
                                modification.getTimestamp(),
                                modification.isDeleted(),
                                value));
            }
            Collections.reverse(revisions);
            return List.copyOf(revisions);
        } finally {
            closeQuietly(iterator);
        }
    }

    /**
     * Constructs a Fabric composite key for the given ID. Composite keys are optimized for prefix
     * queries and help organize data.
     *
     * @param id The entity's unique ID.
     * @return The string representation of the composite key.
     */
    protected String compositeKey(final String id) {
        return stub.createCompositeKey(objectType, id).toString();
    }

    /**
     * Helper to safely close Fabric ledger iterators.
     *
     * @param closeable The iterator or resource to close.
     */
    protected void closeQuietly(final AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (final Exception ignored) {
            // Iterator cleanup should not fail query responses.
        }
    }
}
