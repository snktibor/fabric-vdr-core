package com.example.vdr.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.vdr.e2e.entity.EntityHandlerRegistry;
import com.example.vdr.e2e.support.FabricConnectionManager;
import com.example.vdr.e2e.support.ScenarioContext;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

/** Entity-agnostic Gherkin steps. They delegate to the registry, never to a {@code switch}. */
public class CommonLedgerSteps {

    private final FabricConnectionManager connection;
    private final EntityHandlerRegistry registry;
    private final ScenarioContext context;

    public CommonLedgerSteps(
            final FabricConnectionManager connection,
            final EntityHandlerRegistry registry,
            final ScenarioContext context) {
        this.connection = connection;
        this.registry = registry;
        this.context = context;
    }

    @Given("a connected Fabric Gateway as an {string} client")
    public void aConnectedFabricGateway(final String clientType) throws Exception {
        connection.connect(clientType);
    }

    @Given("a {string} with id {string} exists on the ledger")
    public void anEntityExistsOnTheLedger(final String entityType, final String id) {
        registry.get(entityType).existingOnLedger(id);
    }

    @When("I create a {string} with id {string}")
    public void iCreateAnEntity(final String entityType, final String id) {
        registry.get(entityType).create(id);
    }

    @When("I query the {string} with id {string}")
    public void iQueryTheEntity(final String entityType, final String id) {
        registry.get(entityType).query(id);
    }

    @When("I update the {string} with id {string}")
    public void iUpdateTheEntity(final String entityType, final String id) {
        registry.get(entityType).update(id);
    }

    @When("I deactivate the {string} with id {string}")
    public void iDeactivateTheEntity(final String entityType, final String id) {
        registry.get(entityType).deactivate(id);
    }

    @When("I deactivate the {string} with id {string} using an invalid JWT")
    public void iDeactivateTheEntityWithInvalidJwt(final String entityType, final String id) {
        registry.get(entityType).deactivateWithInvalidJwt(id);
    }

    @Then("the transaction should be successful")
    public void theTransactionShouldBeSuccessful() {
        assertNull(
                context.lastTransactionException(),
                () -> "Transaction failed: " + context.lastTransactionException().getMessage());
    }

    @Then("the transaction should fail with an {string} error")
    public void theTransactionShouldFailWith(final String expectedMessage) {
        final Exception ex = context.lastTransactionException();
        assertNotNull(
                ex,
                () ->
                        "Expected transaction to fail with message '"
                                + expectedMessage
                                + "', but it succeeded.");
        assertTrue(
                ex.getMessage() != null && ex.getMessage().contains(expectedMessage),
                () -> "Transaction failed with unexpected message: " + ex.getMessage());
    }

    @Then("a {string} with id {string} should exist on the ledger")
    public void aEntityExistsAssertion(final String entityType, final String id) {
        registry.get(entityType).assertExists(id);
    }

    @Then("the {string} with id {string} is marked as {string}")
    public void theEntityIsMarkedAs(final String entityType, final String id, final String status) {
        registry.get(entityType).assertStatus(id, status);
    }
}
