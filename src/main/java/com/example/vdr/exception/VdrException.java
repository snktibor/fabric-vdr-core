package com.example.vdr.exception;

/**
 * Domain-level runtime exception used across the VDR chaincode to expose machine-readable failure
 * semantics to contract clients.
 *
 * <p>This type centralizes SSI-specific error categories (for example validation, authorization,
 * and referential integrity failures) behind stable error codes, allowing the contract layer to
 * surface deterministic failure information without coupling callers to internal service
 * implementations.
 */
public class VdrException extends RuntimeException {

    private final String errorCode;

    /**
     * Creates a new domain exception instance with a canonical error code.
     *
     * @param errorCode stable error code intended for client-side classification
     * @param message human-readable description suitable for logs and responses
     */
    private VdrException(final String errorCode, final String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * Creates an exception that indicates input validation failure.
     *
     * <p>Services use this factory for fail-fast argument checks so invalid transactions terminate
     * before ledger access or deeper business flow.
     *
     * @param message validation failure detail for operators and callers
     * @return a {@link VdrException} with {@code INVALID_ARGUMENT} error code
     */
    public static VdrException invalid(final String message) {
        return new VdrException("INVALID_ARGUMENT", message);
    }

    /**
     * Creates an exception for uniqueness violations when an entity id already exists.
     *
     * @param entityType logical entity type (for example DID or SCHEMA)
     * @param id business identifier that conflicts with an existing record
     * @return a {@link VdrException} with {@code ALREADY_EXISTS} error code
     */
    public static VdrException duplicate(final String entityType, final String id) {
        return new VdrException(
                "ALREADY_EXISTS", entityType + " with id '" + id + "' already exists");
    }

    /**
     * Creates an exception for lookup failures when a requested entity is absent.
     *
     * @param entityType logical entity type expected on the ledger
     * @param id business identifier that could not be resolved
     * @return a {@link VdrException} with {@code NOT_FOUND} error code
     */
    public static VdrException notFound(final String entityType, final String id) {
        return new VdrException("NOT_FOUND", entityType + " with id '" + id + "' was not found");
    }

    /**
     * Creates an exception for authorization failures.
     *
     * @param message explanation describing why caller authorization failed
     * @return a {@link VdrException} with {@code UNAUTHORIZED} error code
     */
    public static VdrException unauthorized(final String message) {
        return new VdrException("UNAUTHORIZED", message);
    }

    /**
     * Creates an exception for a chaincode invariant that only a code defect can break.
     *
     * <p>Unlike the other factories this one never reflects caller input: it marks a condition the
     * contract's own logic must uphold, failing the transaction outright rather than letting the
     * violation pass silently.
     *
     * @param message description of the violated invariant
     * @return a {@link VdrException} with {@code INTERNAL} error code
     */
    public static VdrException internal(final String message) {
        return new VdrException("INTERNAL", message);
    }

    /**
     * Returns the stable domain error code associated with this exception.
     *
     * <p>Contract responses can use this value to keep client-side error handling deterministic
     * even when message text evolves.
     *
     * @return canonical error code for programmatic failure classification
     */
    public String getErrorCode() {
        return errorCode;
    }
}
