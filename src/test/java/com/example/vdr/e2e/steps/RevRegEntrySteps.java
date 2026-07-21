package com.example.vdr.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.vdr.TestVectors;
import com.example.vdr.e2e.entity.EntityHandlerRegistry;
import com.example.vdr.e2e.entity.RevRegEntryEntityHandler;
import com.example.vdr.e2e.support.ChaincodeEventProbe;
import com.example.vdr.e2e.support.ScenarioContext;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

/** Steps unique to the revocation registry entry (accumulator) lifecycle. */
public class RevRegEntrySteps {

    private final EntityHandlerRegistry registry;
    private final ScenarioContext context;
    private final ChaincodeEventProbe eventProbe;

    public RevRegEntrySteps(
            final EntityHandlerRegistry registry,
            final ScenarioContext context,
            final ChaincodeEventProbe eventProbe) {
        this.registry = registry;
        this.context = context;
        this.eventProbe = eventProbe;
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

    @When(
            "I update the accumulator of the {string} with id {string} to {string} while listening"
                    + " for chaincode events")
    public void iUpdateTheAccumulatorWhileListening(
            final String entityType, final String id, final String newAccumValue) {
        requireRevRegEntryType(entityType);
        final String revRegDefId = context.resolveId(id);
        eventProbe.runAndAwaitEvent(
                "RevRegEntryUpdated",
                "\"revRegDefId\":\"" + revRegDefId + "\"",
                () -> registry.revRegEntry().updateAccumulator(id, newAccumValue));
    }

    @Then("a {string} chaincode event should be received with accumulator value {string}")
    public void aChaincodeEventShouldBeReceived(
            final String expectedEventName, final String expectedAccumAlias) {
        final String expectedAccumValue = TestVectors.accumValue(expectedAccumAlias);
        assertNotNull(
                context.lastChaincodeEventName(),
                () ->
                        "No matching '"
                                + expectedEventName
                                + "' chaincode event was received before the timeout.");
        assertEquals(expectedEventName, context.lastChaincodeEventName());
        final String payload = context.lastChaincodeEventPayload();
        assertTrue(
                payload.contains("\"accumValue\":\"" + expectedAccumValue + "\""),
                () -> "Event payload did not carry the expected accumulator: " + payload);
    }

    @When("I fetch the accumulator history of the {string} with id {string}")
    public void iFetchTheAccumulatorHistory(final String entityType, final String id) {
        requireRevRegEntryType(entityType);
        registry.revRegEntry().fetchHistory(id);
    }

    @Then(
            "the history should list accumulator value {string} with version {long} before"
                    + " accumulator value {string} with version {long}")
    public void theHistoryShouldListInOrder(
            final String earlierAccumAlias,
            final long earlierVersion,
            final String laterAccumAlias,
            final long laterVersion) {
        final String earlierAccumValue = TestVectors.accumValue(earlierAccumAlias);
        final String laterAccumValue = TestVectors.accumValue(laterAccumAlias);
        final String history = context.lastQueryResponse();
        assertNotNull(history, "No history response captured; run the fetch step first.");
        final int earlierIndex = history.indexOf("\"accumValue\":\"" + earlierAccumValue + "\"");
        final int laterIndex = history.indexOf("\"accumValue\":\"" + laterAccumValue + "\"");
        assertTrue(
                earlierIndex >= 0 && laterIndex >= 0,
                () -> "History did not contain both accumulator values: " + history);
        assertTrue(
                earlierIndex < laterIndex,
                () -> "History revisions are not in oldest-first order: " + history);
        assertTrue(
                history.contains("\"version\":" + earlierVersion)
                        && history.contains("\"version\":" + laterVersion),
                () -> "History did not contain the expected versions: " + history);
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
