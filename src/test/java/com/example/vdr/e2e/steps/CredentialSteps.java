package com.example.vdr.e2e.steps;

import com.example.vdr.e2e.entity.CredentialEntityHandler;
import com.example.vdr.e2e.entity.EntityHandlerRegistry;
import io.cucumber.java.en.When;

/**
 * Steps unique to the credential lifecycle. Issuance, querying, listing, status assertions and
 * generic failure assertions are covered by {@link CommonLedgerSteps} and {@link ListQuerySteps};
 * verification and revocation require credential-specific endpoints and are wired here.
 */
public class CredentialSteps {

    private final EntityHandlerRegistry registry;

    public CredentialSteps(final EntityHandlerRegistry registry) {
        this.registry = registry;
    }

    @When("I verify the {string} with id {string}")
    public void iVerifyTheCredential(final String entityType, final String id) {
        requireCredential(entityType, "verify").verify(id);
    }

    @When("I verify the {string} with id {string} with a tampered payload")
    public void iVerifyTheCredentialWithTamperedPayload(final String entityType, final String id) {
        requireCredential(entityType, "verify with a tampered payload")
                .verifyWithTamperedPayload(id);
    }

    @When("I revoke the {string} with id {string}")
    public void iRevokeTheCredential(final String entityType, final String id) {
        requireCredential(entityType, "revoke").revoke(id);
    }

    @When("I revoke the {string} with id {string} using an invalid JWT")
    public void iRevokeTheCredentialWithInvalidJwt(final String entityType, final String id) {
        requireCredential(entityType, "revoke with an invalid JWT").revokeWithInvalidJwt(id);
    }

    private CredentialEntityHandler requireCredential(final String entityType, final String op) {
        if (!CredentialEntityHandler.TYPE.equals(entityType)) {
            throw new IllegalArgumentException(
                    "'" + op + "' is only supported for credential; got: " + entityType);
        }
        return registry.credential();
    }
}
