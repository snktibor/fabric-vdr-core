package com.example.vdr.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.example.vdr.TestVectors;
import com.example.vdr.e2e.entity.EntityHandlerRegistry;
import com.example.vdr.e2e.support.ConcurrentSubmission;
import com.example.vdr.e2e.support.RetryPolicy;
import com.example.vdr.e2e.support.ScenarioContext;
import com.example.vdr.e2e.support.TransactionExecutor;
import com.example.vdr.e2e.support.TransactionOutcome;
import com.example.vdr.e2e.support.TransactionResult;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Steps for the concurrency scenarios: racing writes on one registry, and the check-then-act window
 * between two registries.
 *
 * <p>Everything these steps learn is kept for the assertion message. A concurrency failure that
 * reports only "failed" is indistinguishable from a broken fixture, so each assertion quotes the
 * per-submission outcome, the peer validation code, the block number and the ledger state that was
 * read before and after.
 */
public class ConcurrencySteps {

    private static final String UPDATE_TX = "updateRevRegEntry";
    private static final Pattern VERSION_PATTERN = Pattern.compile("\"version\"\\s*:\\s*(\\d+)");

    private final EntityHandlerRegistry registry;
    private final ScenarioContext context;
    private final TransactionExecutor executor;

    private final Map<String, Long> observations = new LinkedHashMap<>();
    private final List<String> abandonedRevocations = new ArrayList<>();
    private final List<TransactionResult> sequentialResults = new ArrayList<>();
    private List<TransactionResult> concurrentResults = List.of();

    public ConcurrencySteps(
            final EntityHandlerRegistry registry,
            final ScenarioContext context,
            final TransactionExecutor executor) {
        this.registry = registry;
        this.context = context;
        this.executor = executor;
    }

    @Given("retrying is disabled for this scenario")
    public void retryingIsDisabled() {
        executor.setRetryPolicy(RetryPolicy.none());
    }

    @Given("an independent revocation registry {string} exists with its own issuer")
    public void anIndependentRegistryExists(final String handle) {
        registry.independentRegistry().provision(handle);
    }

    @When("the issuer of {string} observes that {string} is still unrevoked")
    public void theIssuerObservesTheOtherRegistry(final String observer, final String target) {
        final long version = currentVersion(target);
        observations.put(observer + " read " + target, version);
        assertEquals(
                0L,
                version,
                () ->
                        "Pre-condition of the check-then-act window: '"
                                + target
                                + "' had to be unrevoked when '"
                                + observer
                                + "' looked, but its entry is already at version "
                                + version
                                + ". "
                                + diagnostics());
    }

    @When("the issuer of {string} revokes it with {string} only if {string} is still unrevoked")
    public void theIssuerRevokesGuardedByTheOtherRegistry(
            final String handle, final String accumAlias, final String target) {
        final long targetVersion = currentVersion(target);
        observations.put(handle + " read " + target, targetVersion);
        if (targetVersion != 0L) {
            abandonedRevocations.add(
                    handle
                            + " (because "
                            + target
                            + " was already at version "
                            + targetVersion
                            + ")");
            return;
        }
        sequentialResults.add(
                executor.submitSignedForOutcome(
                        handle,
                        UPDATE_TX,
                        ownerOf(handle),
                        keyPairOf(handle),
                        revRegDefIdOf(handle),
                        TestVectors.accumValue(accumAlias)));
    }

    @When(
            "the following accumulator updates are endorsed against the same state and submitted"
                    + " together:")
    public void theUpdatesAreSubmittedTogether(final DataTable table) {
        final List<ConcurrentSubmission> submissions = new ArrayList<>();
        for (final Map<String, String> row : table.asMaps()) {
            final String handle = row.get("registry");
            submissions.add(
                    ConcurrentSubmission.signed(
                            row.get("label"),
                            UPDATE_TX,
                            ownerOf(handle),
                            keyPairOf(handle),
                            revRegDefIdOf(handle),
                            TestVectors.accumValue(row.get("accumulator"))));
        }
        concurrentResults = executor.submitTogether(submissions);
    }

    @Then("no concurrent submission should have failed on endorsement or on the network")
    public void noSubmissionFailedForAnUnrelatedReason() {
        requireConcurrentResults();
        final List<TransactionResult> unrelated =
                concurrentResults.stream()
                        .filter(
                                r ->
                                        r.outcome() == TransactionOutcome.ENDORSEMENT_REJECTED
                                                || r.outcome() == TransactionOutcome.NETWORK_FAILURE
                                                || r.outcome()
                                                        == TransactionOutcome.COMMIT_STATUS_UNKNOWN
                                                || r.outcome() == TransactionOutcome.OTHER_FAILURE)
                        .collect(Collectors.toList());
        assertTrue(
                unrelated.isEmpty(),
                () ->
                        "The race is only evidence if every submission reached validation. "
                                + unrelated.size()
                                + " did not. "
                                + diagnostics());
    }

    @Then("exactly {int} concurrent submission(s) should be committed")
    public void exactlyNSubmissionsCommitted(final int expected) {
        requireConcurrentResults();
        final long committed =
                concurrentResults.stream().filter(TransactionResult::isCommitted).count();
        assertEquals(
                (long) expected,
                committed,
                () ->
                        "Expected exactly "
                                + expected
                                + " of the "
                                + concurrentResults.size()
                                + " concurrent submissions to commit, but "
                                + committed
                                + " did. "
                                + diagnostics());
    }

    @Then("{int} concurrent submission(s) should have outcome {string}")
    public void nSubmissionsHaveOutcome(final int expected, final String outcomeName) {
        requireConcurrentResults();
        final TransactionOutcome expectedOutcome = TransactionOutcome.valueOf(outcomeName);
        final long actual =
                concurrentResults.stream().filter(r -> r.outcome() == expectedOutcome).count();
        assertEquals(
                (long) expected,
                actual,
                () ->
                        "Expected "
                                + expected
                                + " submission(s) to end as "
                                + expectedOutcome
                                + ", but "
                                + actual
                                + " did. "
                                + diagnostics());
    }

    @Then("at most {int} of the revocations should have taken effect")
    public void atMostNRevocationsTookEffect(final int allowed) {
        final List<String> handles = registry.independentRegistry().provisionedHandles();
        if (handles.isEmpty()) {
            fail("No independent registries were provisioned; this step has nothing to count.");
        }
        final List<String> revoked =
                handles.stream().filter(h -> currentVersion(h) > 0L).collect(Collectors.toList());
        assertTrue(
                revoked.size() <= allowed,
                () ->
                        "Conditional revocation requires that at most "
                                + allowed
                                + " of the two independent registries may end up revoked when both"
                                + " issuers observed the other one unrevoked before acting. "
                                + revoked.size()
                                + " were revoked: "
                                + revoked
                                + ". "
                                + diagnostics());
    }

    @Then("the registry {string} should be at version {long}")
    public void theRegistryShouldBeAtVersion(final String handle, final long expected) {
        final long actual = currentVersion(handle);
        assertEquals(
                expected,
                actual,
                () ->
                        "Registry '"
                                + handle
                                + "' should be at version "
                                + expected
                                + " but is at "
                                + actual
                                + ". "
                                + diagnostics());
    }

    @Then("the last transaction outcome should be {string}")
    public void theLastTransactionOutcomeShouldBe(final String outcomeName) {
        final TransactionResult result = context.lastTransactionResult();
        assertNotNull(result, "No classified transaction result was recorded.");
        assertEquals(
                TransactionOutcome.valueOf(outcomeName),
                result.outcome(),
                () -> "Unexpected transaction outcome: " + result.describe());
    }

    private void requireConcurrentResults() {
        if (concurrentResults.isEmpty()) {
            fail("No concurrent submissions were made; run the submit-together step first.");
        }
    }

    private String revRegDefIdOf(final String handle) {
        return context.resolveId(handle);
    }

    private String ownerOf(final String handle) {
        return context.requireOwner(revRegDefIdOf(handle));
    }

    private KeyPair keyPairOf(final String handle) {
        return context.requireDidKeyPair(ownerOf(handle));
    }

    /** Reads the entry's current audit version straight from the ledger. */
    private long currentVersion(final String handle) {
        final String revRegDefId = revRegDefIdOf(handle);
        final String response;
        try {
            response = executor.evaluate("getRevRegEntry", revRegDefId);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not read the entry of '" + handle + "' (" + revRegDefId + ")", e);
        }
        final Matcher matcher = VERSION_PATTERN.matcher(response);
        if (!matcher.find()) {
            throw new IllegalStateException(
                    "No version field in the entry of '" + handle + "': " + response);
        }
        return Long.parseLong(matcher.group(1));
    }

    /**
     * Everything known about the run, so a red assertion names its cause instead of implying it.
     */
    private String diagnostics() {
        final StringBuilder sb = new StringBuilder("\n--- concurrency diagnostics ---");
        sb.append("\nretry policy: ").append(executor.retryPolicy());
        if (!observations.isEmpty()) {
            sb.append("\npre-submit observations (the 'check' of check-then-act):");
            observations.forEach(
                    (k, v) -> sb.append("\n  ").append(k).append(" -> version ").append(v));
        }
        if (!abandonedRevocations.isEmpty()) {
            sb.append("\nrevocations abandoned after re-checking:");
            abandonedRevocations.forEach(a -> sb.append("\n  ").append(a));
        }
        if (!sequentialResults.isEmpty()) {
            sb.append("\nsequential submissions:");
            sequentialResults.forEach(r -> sb.append("\n  ").append(r.describe()));
        }
        if (!concurrentResults.isEmpty()) {
            sb.append("\nconcurrent submissions:");
            concurrentResults.forEach(r -> sb.append("\n  ").append(r.describe()));
        }
        final List<String> handles = registry.independentRegistry().provisionedHandles();
        if (!handles.isEmpty()) {
            sb.append("\nledger state now:");
            for (final String handle : handles) {
                sb.append("\n  ")
                        .append(handle)
                        .append(" (")
                        .append(revRegDefIdOf(handle))
                        .append(") -> version ")
                        .append(currentVersion(handle));
            }
        }
        return sb.append("\n-------------------------------").toString();
    }
}
