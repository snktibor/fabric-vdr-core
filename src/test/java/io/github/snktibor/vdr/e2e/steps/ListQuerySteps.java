package io.github.snktibor.vdr.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.github.snktibor.vdr.e2e.entity.EntityHandlerRegistry;
import io.github.snktibor.vdr.e2e.support.ScenarioContext;

/**
 * Entity-agnostic list/query grammar built on {@link
 * io.github.snktibor.vdr.e2e.entity.EntityHandler#list()}.
 */
public class ListQuerySteps {

    private final EntityHandlerRegistry registry;
    private final ScenarioContext context;

    public ListQuerySteps(final EntityHandlerRegistry registry, final ScenarioContext context) {
        this.registry = registry;
        this.context = context;
    }

    @When("I list all {string} entries")
    public void iListAll(final String entityType) {
        registry.get(entityType).list();
    }

    @Then("the list should contain the {string} with id {string}")
    public void listShouldContain(final String entityType, final String rawId) {
        assertNull(
                context.lastTransactionException(),
                () -> "List query failed: " + context.lastTransactionException().getMessage());
        final String response = context.lastQueryResponse();
        assertNotNull(response, "No list response captured - did the When-step run?");
        final String resolved = context.resolveId(rawId);
        assertTrue(
                response.contains(resolved),
                () ->
                        String.format(
                                "Expected list of '%s' to contain id '%s' but response was: %s",
                                entityType, resolved, response));
    }
}
