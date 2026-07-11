package com.example.vdr.e2e.steps;

import com.example.vdr.e2e.entity.EntityHandlerRegistry;
import com.example.vdr.e2e.entity.RevRegEntryEntityHandler;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

/** Steps unique to the revocation registry entry (accumulator) lifecycle. */
public class RevRegEntrySteps {

    private final EntityHandlerRegistry registry;

    public RevRegEntrySteps(final EntityHandlerRegistry registry) {
        this.registry = registry;
    }

    @When("I update the accumulator of the {string} with id {string} to {string}")
    public void iUpdateTheAccumulator(
            final String entityType, final String id, final String newAccumValue) {
        requireRevRegEntryType(entityType);
        registry.revRegEntry().updateAccumulator(id, newAccumValue);
    }

    @When("I update the accumulator of the {string} with id {string} as an unauthorized actor")
    public void iUpdateTheAccumulatorAsUnauthorizedActor(final String entityType, final String id) {
        requireRevRegEntryType(entityType);
        registry.revRegEntry().updateAccumulatorWithUnauthorizedActor(id);
    }

    @Then("the {string} with id {string} should have accumulator value {string} and version {long}")
    public void shouldHaveAccumulatorValueAndVersion(
            final String entityType,
            final String id,
            final String expectedAccumValue,
            final long expectedVersion) {
        requireRevRegEntryType(entityType);
        registry.revRegEntry().assertAccumulator(id, expectedAccumValue, expectedVersion);
    }

    private static void requireRevRegEntryType(final String entityType) {
        if (!RevRegEntryEntityHandler.TYPE.equals(entityType)) {
            throw new IllegalArgumentException(
                    "accumulator steps are only supported for "
                            + RevRegEntryEntityHandler.TYPE
                            + "; got: "
                            + entityType);
        }
    }
}
