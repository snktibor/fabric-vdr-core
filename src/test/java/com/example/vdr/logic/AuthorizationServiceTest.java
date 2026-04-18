package com.example.vdr.logic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.vdr.exception.VdrException;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AuthorizationService} owner-check behavior.
 *
 * <p>Validates unauthorized outcomes for missing/mismatched owners and success for matching
 * caller-owner identities.
 */
@SuppressWarnings("java:S1192")
class AuthorizationServiceTest {

    private final AuthorizationService authorizationService = new AuthorizationService();

    /** Verifies missing owner metadata is rejected as unauthorized. */
    @Test
    void assertCallerIsOwnerRejectsMissingOwner() {
        final VdrException exception =
                assertThrows(
                        VdrException.class,
                        () ->
                                authorizationService.assertCallerIsOwner(
                                        "caller", null, "did", "did:1"));

        assertEquals("UNAUTHORIZED", exception.getErrorCode());
    }

    /** Verifies owner mismatch is rejected as unauthorized. */
    @Test
    void assertCallerIsOwnerRejectsDifferentOwner() {
        final VdrException exception =
                assertThrows(
                        VdrException.class,
                        () ->
                                authorizationService.assertCallerIsOwner(
                                        "caller", "owner", "schema", "schema-1"));

        assertEquals("UNAUTHORIZED", exception.getErrorCode());
    }

    /** Verifies access is allowed when caller matches recorded owner. */
    @Test
    void assertCallerIsOwnerAllowsOwner() {
        assertDoesNotThrow(
                () ->
                        authorizationService.assertCallerIsOwner(
                                "caller", "caller", "cred", "cred-1"));
    }
}
