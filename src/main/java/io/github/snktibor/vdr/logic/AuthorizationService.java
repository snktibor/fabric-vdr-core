package io.github.snktibor.vdr.logic;

import io.github.snktibor.vdr.exception.VdrException;

/**
 * Layer-3 authorization helper for owner-based access control.
 *
 * <p>This service centralizes the ownership rule used by write/update/deactivate operations so
 * every domain service enforces the same authorization semantics. Ownership checks are based on
 * authenticated DID signer identities, not transport-layer X.509 caller identifiers.
 */
public class AuthorizationService {

    /**
     * Verifies that the authenticated signer DID matches the recorded owner of an entity.
     *
     * <p>The check is fail-fast: missing owner metadata and owner mismatch are both rejected
     * immediately with deterministic unauthorized errors.
     *
     * @param signerDid DID resolved from verified JWT issuer claim
     * @param ownerId owner identity recorded on the target entity
     * @param entityType logical entity type used in error context
     * @param id business identifier of the protected entity
     * @throws VdrException if owner metadata is missing or caller is not owner
     */
    public void assertSignerOwnsEntity(
            final String signerDid,
            final String ownerId,
            final String entityType,
            final String id) {
        if (ownerId == null || ownerId.isBlank()) {
            throw VdrException.unauthorized(
                    "Unauthorized: owner information missing for " + entityType + " '" + id + "'");
        }
        if (!ownerId.equals(signerDid)) {
            throw VdrException.unauthorized(
                    "Unauthorized: signer DID is not the owner of " + entityType + " '" + id + "'");
        }
    }
}
