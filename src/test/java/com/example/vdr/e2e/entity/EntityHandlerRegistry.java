package com.example.vdr.e2e.entity;

import com.example.vdr.e2e.support.ScenarioContext;
import com.example.vdr.e2e.support.TransactionExecutor;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Strategy lookup that replaces the original {@code switch} statements while preserving their
 * single point of extension. Adding a new entity type is a one-line {@link #register} call.
 *
 * <p>Instantiated once per scenario by PicoContainer; handlers are stateless w.r.t. the test
 * framework and only depend on the per-scenario {@link ScenarioContext} / {@link
 * TransactionExecutor} injected here.
 */
public class EntityHandlerRegistry {

    private final Map<String, EntityHandler> handlers = new LinkedHashMap<>();

    public EntityHandlerRegistry(
            final TransactionExecutor executor, final ScenarioContext context) {
        final DidEntityHandler didHandler = new DidEntityHandler(executor, context);
        final SchemaEntityHandler schemaHandler =
                new SchemaEntityHandler(executor, context, didHandler);
        final CredDefEntityHandler credDefHandler =
                new CredDefEntityHandler(executor, context, didHandler, schemaHandler);
        final RevRegDefEntityHandler revRegDefHandler =
                new RevRegDefEntityHandler(executor, context, didHandler, credDefHandler);
        final RevRegEntryEntityHandler revRegEntryHandler =
                new RevRegEntryEntityHandler(executor, context, didHandler, revRegDefHandler);
        register(didHandler);
        register(schemaHandler);
        register(credDefHandler);
        register(revRegDefHandler);
        register(revRegEntryHandler);
    }

    private void register(final EntityHandler handler) {
        handlers.put(handler.typeKey(), handler);
    }

    public EntityHandler get(final String typeKey) {
        final EntityHandler handler = handlers.get(typeKey);
        if (handler == null) {
            throw new IllegalArgumentException("Unsupported entity type: " + typeKey);
        }
        return handler;
    }

    /** Direct accessor for the DID handler — useful for DID-specific steps. */
    public DidEntityHandler did() {
        return (DidEntityHandler) get(DidEntityHandler.TYPE);
    }

    /** Direct accessor for the schema handler — useful for schema-specific steps. */
    public SchemaEntityHandler schema() {
        return (SchemaEntityHandler) get(SchemaEntityHandler.TYPE);
    }

    /** Direct accessor for the revocation registry entry handler — useful for accumulator steps. */
    public RevRegEntryEntityHandler revRegEntry() {
        return (RevRegEntryEntityHandler) get(RevRegEntryEntityHandler.TYPE);
    }
}
