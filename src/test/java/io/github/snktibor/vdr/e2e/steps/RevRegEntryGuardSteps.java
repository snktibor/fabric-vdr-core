package io.github.snktibor.vdr.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.github.snktibor.vdr.e2e.entity.EntityHandlerRegistry;
import io.github.snktibor.vdr.e2e.support.ChaincodeEventProbe;
import io.github.snktibor.vdr.e2e.support.GuardedUpdateResponse;
import io.github.snktibor.vdr.e2e.support.GuardsJson;
import io.github.snktibor.vdr.e2e.support.RevRegEntryProbe;
import io.github.snktibor.vdr.e2e.support.ScenarioContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Steps for the guarded accumulator update — the opt-in path that writes only if every registry it
 * names still stands at the version the caller observed.
 *
 * <p>Expected versions are observed rather than written into the feature file. A literal would keep
 * passing for the wrong reason the moment a fixture gained a step, and the guard's whole claim is
 * about a version somebody actually read; the {@code observed} keyword in a guard table therefore
 * resolves to what the observation step read, and a number is reserved for the cases that
 * deliberately pin a version nobody could have observed.
 *
 * <p>Registry handles are scenario-facing names. Identifiers are minted at run time, so wherever
 * one has to appear inside a raw payload or an expected error message it is spliced in from the
 * handle.
 */
public class RevRegEntryGuardSteps {

    private static final String OBSERVED = "observed";
    private static final String REGISTRY_COLUMN = "registry";
    private static final String EXPECTED_VERSION_COLUMN = "expected version";
    private static final String UPDATED_EVENT = "RevRegEntryUpdated";
    private static final Pattern HANDLE_PATTERN = Pattern.compile("<([^<>]+)>");

    private final EntityHandlerRegistry registry;
    private final ScenarioContext context;
    private final RevRegEntryProbe probe;
    private final ChaincodeEventProbe eventProbe;

    private final Map<String, Long> observedVersions = new LinkedHashMap<>();

    public RevRegEntryGuardSteps(
            final EntityHandlerRegistry registry,
            final ScenarioContext context,
            final RevRegEntryProbe probe,
            final ChaincodeEventProbe eventProbe) {
        this.registry = registry;
        this.context = context;
        this.probe = probe;
        this.eventProbe = eventProbe;
    }

    @When("the version of {string} is observed")
    public void theVersionIsObserved(final String handle) {
        observedVersions.put(handle, probe.version(context.resolveId(handle)));
    }

    @When("the issuer of {string} updates its accumulator to {string} guarded by:")
    public void theIssuerUpdatesItsAccumulatorGuardedBy(
            final String handle, final String accumAlias, final DataTable guards) {
        registry.revRegEntry()
                .updateAccumulatorIfGuardsUnchanged(handle, accumAlias, guardsFrom(guards));
    }

    @When(
            "the issuer of {string} updates its accumulator to {string} while listening for"
                    + " chaincode events, guarded by:")
    public void theIssuerUpdatesItsAccumulatorWhileListeningGuardedBy(
            final String handle, final String accumAlias, final DataTable guards) {
        final GuardsJson guardsJson = guardsFrom(guards);
        final String revRegDefId = context.resolveId(handle);
        eventProbe.runAndAwaitEvent(
                UPDATED_EVENT,
                "\"revRegDefId\":\"" + revRegDefId + "\"",
                () ->
                        registry.revRegEntry()
                                .updateAccumulatorIfGuardsUnchanged(
                                        handle, accumAlias, guardsJson));
    }

    @When(
            "a registered non-owner issuer updates the accumulator of {string} to {string} guarded by:")
    public void aNonOwnerUpdatesTheAccumulatorGuardedBy(
            final String handle, final String accumAlias, final DataTable guards) {
        registry.revRegEntry()
                .updateAccumulatorIfGuardsUnchangedWithUnauthorizedActor(
                        handle, accumAlias, guardsFrom(guards));
    }

    /**
     * Submits the guard argument exactly as written in the feature file.
     *
     * <p>Some payloads cannot be produced by building objects and serialising them — a duplicated
     * JSON key is the clearest case, since writers collapse it to one entry before it ever reaches
     * the wire. The digest is taken over this same text, so what the chaincode rejects is the
     * shape, not a scope mismatch the harness introduced.
     */
    @When("the issuer of {string} updates its accumulator to {string} guarded by the raw payload:")
    public void theIssuerUpdatesItsAccumulatorGuardedByARawPayload(
            final String handle, final String accumAlias, final String rawGuards) {
        registry.revRegEntry()
                .updateAccumulatorIfGuardsUnchanged(
                        handle, accumAlias, GuardsJson.raw(resolveHandles(rawGuards)));
    }

    /**
     * Sends two guarded updates in a row, the second reading its expected versions out of the first
     * one's response and nothing else.
     *
     * <p>There is deliberately no query between the calls: the point is that the answer a guarded
     * update returns is by itself enough to continue from. The two version fields are not
     * interchangeable — the guard on the target takes {@code entry.version}, every other guard
     * takes its own {@code checkedVersion}.
     */
    @When(
            "the issuer of {string} chains two guarded updates to {string} and {string}, guarded by"
                    + " itself and {string}, taking the second call's expected versions from the"
                    + " first response")
    public void theIssuerChainsTwoGuardedUpdates(
            final String target,
            final String firstAccumAlias,
            final String secondAccumAlias,
            final String external) {
        final GuardedUpdateResponse first = firstCallOfTheChain(target, firstAccumAlias, external);
        final String targetId = context.resolveId(target);
        final String externalId = context.resolveId(external);
        registry.revRegEntry()
                .updateAccumulatorIfGuardsUnchanged(
                        target,
                        secondAccumAlias,
                        GuardsJson.builder()
                                .guard(targetId, first.entryVersion())
                                .guard(externalId, first.checkedVersion(externalId))
                                .build());
    }

    /**
     * The negative twin of the chained call: the second update pins its own guard to the checked
     * version instead of the entry version. For a guard on the target the checked version is the
     * pre-mutation one, so it is always exactly one behind — a caller that reads the wrong field is
     * refused rather than quietly overwriting its own write.
     */
    @When(
            "the issuer of {string} chains two guarded updates to {string} and {string}, guarded by"
                    + " itself and {string}, sending the first response's checked version for its"
                    + " own guard on the second call")
    public void theIssuerChainsTwoGuardedUpdatesReusingTheCheckedVersion(
            final String target,
            final String firstAccumAlias,
            final String secondAccumAlias,
            final String external) {
        final GuardedUpdateResponse first = firstCallOfTheChain(target, firstAccumAlias, external);
        final String targetId = context.resolveId(target);
        final String externalId = context.resolveId(external);
        registry.revRegEntry()
                .updateAccumulatorIfGuardsUnchanged(
                        target,
                        secondAccumAlias,
                        GuardsJson.builder()
                                .guard(targetId, first.checkedVersion(targetId))
                                .guard(externalId, first.checkedVersion(externalId))
                                .build());
    }

    /**
     * The chained call with the guarded registry moving underneath it: its own issuer writes to it
     * over the unguarded path after the first call answered and before the second one is sent.
     *
     * <p>Carrying a version over from an earlier response is safe because it is checked again, not
     * because the earlier call promised anything about the time since. The first call guarantees
     * the guarded registry was unchanged up to its own commit and nothing beyond it, so the number
     * it reported is a claim the second call has to re-earn. Every write moves the version, so a
     * registry that moved cannot match, and the second call is refused rather than writing against
     * a state nobody checked.
     *
     * <p>The two guarded calls are issued from here for the same reason the plain chain is: no
     * query may run between them, and keeping both inside one step is what makes that visible.
     */
    @When(
            "the issuer of {string} chains two guarded updates to {string} and {string}, guarded by"
                    + " itself and {string}, whose issuer moves it to {string} between the two"
                    + " calls")
    public void theIssuerChainsTwoGuardedUpdatesAcrossAMovingGuard(
            final String target,
            final String firstAccumAlias,
            final String secondAccumAlias,
            final String external,
            final String interveningAccumAlias) {
        final GuardedUpdateResponse first = firstCallOfTheChain(target, firstAccumAlias, external);
        final String targetId = context.resolveId(target);
        final String externalId = context.resolveId(external);
        final long chainedTargetVersion = first.entryVersion();
        final long chainedExternalVersion = first.checkedVersion(externalId);

        registry.revRegEntry().updateAccumulator(external, interveningAccumAlias);
        assertNull(
                context.lastTransactionException(),
                () ->
                        "The guarded registry had to move between the two calls for this to"
                                + " measure anything, but its own issuer could not write to it: "
                                + context.lastTransactionException().getMessage());

        registry.revRegEntry()
                .updateAccumulatorIfGuardsUnchanged(
                        target,
                        secondAccumAlias,
                        GuardsJson.builder()
                                .guard(targetId, chainedTargetVersion)
                                .guard(externalId, chainedExternalVersion)
                                .build());
    }

    @Then("the guarded response should report entry version {long}")
    public void theGuardedResponseShouldReportEntryVersion(final long expected) {
        final GuardedUpdateResponse response = requireGuardedResponse();
        assertEquals(
                expected,
                response.entryVersion(),
                () -> "Unexpected entry version in the guarded response: " + response.raw());
    }

    @Then("the guarded response should report checked version {long} for {string}")
    public void theGuardedResponseShouldReportCheckedVersion(
            final long expected, final String handle) {
        final GuardedUpdateResponse response = requireGuardedResponse();
        assertEquals(
                expected,
                response.checkedVersion(context.resolveId(handle)),
                () ->
                        "Unexpected checked version for '"
                                + handle
                                + "' in the guarded response: "
                                + response.raw());
    }

    @Then(
            "the transaction should fail because {string} is at version {long} and {long} was expected")
    public void theTransactionShouldFailOnAStaleGuard(
            final String handle, final long found, final long expected) {
        assertFailureMessageContains(
                "guard registry '"
                        + context.resolveId(handle)
                        + "' is at version "
                        + found
                        + ", expected "
                        + expected
                        + ": it has changed since the state this operation was tested against");
    }

    @Then("the transaction should fail with a not-found error naming {string}")
    public void theTransactionShouldFailWithANotFoundErrorNaming(final String handle) {
        assertFailureMessageContains(
                "revregentry with id '" + context.resolveId(handle) + "' was not found");
    }

    @Then("the transaction failure message should not contain {string}")
    public void theTransactionFailureMessageShouldNotContain(final String fragment) {
        final String message = requireFailureMessage();
        assertTrue(
                !message.contains(fragment),
                () ->
                        "The failure message was expected to say nothing about '"
                                + fragment
                                + "', but it read: "
                                + message);
    }

    /** Runs the first update of a chain and requires it to have committed. */
    private GuardedUpdateResponse firstCallOfTheChain(
            final String target, final String accumAlias, final String external) {
        final String response =
                registry.revRegEntry()
                        .updateAccumulatorIfGuardsUnchanged(
                                target,
                                accumAlias,
                                GuardsJson.builder()
                                        .guard(context.resolveId(target), requireObserved(target))
                                        .guard(
                                                context.resolveId(external),
                                                requireObserved(external))
                                        .build());
        assertNotNull(
                response,
                () ->
                        "The chain can only be measured if its first update committed, but it"
                                + " failed: "
                                + (context.lastTransactionException() == null
                                        ? "no exception recorded"
                                        : context.lastTransactionException().getMessage()));
        return GuardedUpdateResponse.parse(response);
    }

    private GuardsJson guardsFrom(final DataTable table) {
        final GuardsJson.Builder builder = GuardsJson.builder();
        for (final Map<String, String> row : table.asMaps()) {
            final String handle = row.get(REGISTRY_COLUMN);
            builder.guard(
                    context.resolveId(handle),
                    expectedVersion(handle, row.get(EXPECTED_VERSION_COLUMN)));
        }
        return builder.build();
    }

    private long expectedVersion(final String handle, final String cell) {
        if (OBSERVED.equals(cell)) {
            return requireObserved(handle);
        }
        return Long.parseLong(cell.trim());
    }

    private long requireObserved(final String handle) {
        final Long observed = observedVersions.get(handle);
        if (observed == null) {
            throw new IllegalStateException(
                    "The scenario guards '"
                            + handle
                            + "' at the version it observed, but no observation step ran for it.");
        }
        return observed;
    }

    /** Splices run-time identifiers into a payload written against scenario handles. */
    private String resolveHandles(final String text) {
        final Matcher matcher = HANDLE_PATTERN.matcher(text);
        final StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(
                    resolved, Matcher.quoteReplacement(context.resolveId(matcher.group(1))));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private GuardedUpdateResponse requireGuardedResponse() {
        final String payload = registry.revRegEntry().lastGuardedResponse();
        assertNotNull(
                payload,
                () ->
                        "No guarded update response was captured; the last guarded submission did"
                                + " not commit.");
        return GuardedUpdateResponse.parse(payload);
    }

    private void assertFailureMessageContains(final String fragment) {
        final String message = requireFailureMessage();
        assertTrue(
                message.contains(fragment),
                () ->
                        "Transaction failed, but not with the expected reason.\n  expected to"
                                + " contain: "
                                + fragment
                                + "\n  actual: "
                                + message);
    }

    private String requireFailureMessage() {
        final Exception failure = context.lastTransactionException();
        assertNotNull(failure, "Expected the transaction to fail, but it succeeded.");
        assertNotNull(failure.getMessage(), "The transaction failed without a message.");
        return failure.getMessage();
    }
}
