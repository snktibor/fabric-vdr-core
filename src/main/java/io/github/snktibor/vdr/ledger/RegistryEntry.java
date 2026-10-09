package io.github.snktibor.vdr.ledger;

/**
 * Immutable key-value projection used for registry list/query responses.
 *
 * <p>The entry keeps both the ledger identifier and the typed payload together, which allows upper
 * layers to return deterministic collections without exposing internal storage details of {@link
 * BaseRegistry}.
 *
 * @param <T> domain record type stored in the registry value
 */
public class RegistryEntry<T> {

    private final String id;
    private final T value;

    /**
     * Creates a typed registry entry projection.
     *
     * @param id business identifier resolved from the composite key
     * @param value typed ledger payload associated with the identifier
     */
    public RegistryEntry(final String id, final T value) {
        this.id = id;
        this.value = value;
    }

    /**
     * Returns the business identifier represented by this entry.
     *
     * @return identifier associated with the payload value
     */
    public String getId() {
        return id;
    }

    /**
     * Returns the typed payload represented by this entry.
     *
     * @return domain record value mapped from ledger state
     */
    public T getValue() {
        return value;
    }
}
