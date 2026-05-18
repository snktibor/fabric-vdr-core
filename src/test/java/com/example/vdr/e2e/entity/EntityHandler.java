package com.example.vdr.e2e.entity;

/**
 * Strategy for a single ledger entity type ("DID document", "schema", ...). Adding a new entity
 * means implementing this interface and registering it in {@link EntityHandlerRegistry} — no
 * changes to step classes or feature files are required.
 *
 * <p>Methods that a particular entity does not support should throw {@link
 * UnsupportedOperationException}; {@link AbstractEntityHandler} provides that default.
 */
public interface EntityHandler {

    /** The Gherkin-facing type token, e.g. {@code "DID document"}. */
    String typeKey();

    void create(String rawId);

    void existingOnLedger(String rawId);

    void query(String rawId);

    void update(String rawId);

    void deactivate(String rawId);

    void deactivateWithInvalidJwt(String rawId);

    void assertExists(String rawId);

    void assertStatus(String rawId, String expectedStatus);

    /** Evaluates the entity's list endpoint, storing the raw JSON array in the context. */
    void list();
}
