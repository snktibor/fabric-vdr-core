package com.example.vdr.logic;

import com.example.vdr.exception.VdrException;

/**
 * Layer-3 authorization helper for owner-based access control.
 *
 * <p>This service centralizes the ownership rule used by write/update/deactivate operations so
 * every domain service enforces the same authorization semantics. Keeping this logic in one place
 * reduces duplication and prevents subtle policy drift across DID, schema, credential definition,
 * and credential flows.
 */
public class AuthorizationService {

    /**
     * Verifies that the transaction caller matches the recorded owner of an entity.
     *
     * <p>The check is fail-fast: missing owner metadata and owner mismatch are both rejected
     * immediately with deterministic unauthorized errors.
     *
     * @param callerId invoker identity extracted from transaction context
     * @param ownerId owner identity recorded on the target entity
     * @param entityType logical entity type used in error context
     * @param id business identifier of the protected entity
     * @throws VdrException if owner metadata is missing or caller is not owner
     */
    public void assertCallerIsOwner(
            final String callerId, final String ownerId, final String entityType, final String id) {
        if (ownerId == null || ownerId.isBlank()) {
            throw VdrException.unauthorized(
                    "Unauthorized: owner information missing for " + entityType + " '" + id + "'");
        }
        if (!ownerId.equals(callerId)) {
            throw VdrException.unauthorized(
                    "Unauthorized: Caller is not the owner of " + entityType + " '" + id + "'");
        }
    }
}
