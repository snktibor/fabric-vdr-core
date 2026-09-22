package com.example.vdr.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.example.vdr.TestVectors;
import com.example.vdr.e2e.entity.EntityHandlerRegistry;
import com.example.vdr.e2e.support.ConcurrentSubmission;
import com.example.vdr.e2e.support.GuardsJson;
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
    private static final String GUARDED_UPDATE_TX = "updateRevRegEntryIfGuardsUnchanged";
    private static final String GUARD_COLUMN = "guard";

    /**
     * The tail of the refusal a guard raises. The error code itself never reaches a Gateway client
     * — it travels in the chaincode response payload, while the error detail carries only the
     * status and the message — so the message is what separates a guard that fired from an
     * ownership or argument refusal.
     */
    private static final String GUARD_REFUSAL =
            "it has changed since the state this operation was tested against";

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

    /**
     * Races the listed updates, each one over the path its row asks for.
     *
     * <p>A row with no {@code guard} column, or an empty one, submits the unconditional update
     * exactly as before. A row that names one or more registries — its own included, comma
     * separated — submits the guarded update instead, pinning every named registry to the version
     * this row's issuer observed earlier in the scenario.
     */
    @When(
            "the following accumulator updates are endorsed against the same state and submitted"
                    + " together:")
    public void theUpdatesAreSubmittedTogether(final DataTable table) {
        final List<ConcurrentSubmission> submissions = new ArrayList<>();
        for (final Map<String, String> row : table.asMaps()) {
            final String handle = row.get("registry");
            final String guards = row.get(GUARD_COLUMN);
            final String accumValue = TestVectors.accumValue(row.get("accumulator"));
            if (guards == null || guards.trim().isEmpty()) {
                submissions.add(
                        ConcurrentSubmission.signed(
                                row.get("label"),
                                UPDATE_TX,
                                ownerOf(handle),
                                keyPairOf(handle),
                                revRegDefIdOf(handle),
                                accumValue));
            } else {
                submissions.add(
                        ConcurrentSubmission.signed(
                                row.get("label"),
                                GUARDED_UPDATE_TX,
                                ownerOf(handle),
                                keyPairOf(handle),
                                revRegDefIdOf(handle),
                                accumValue,
                                guardListOf(handle, guards).text()));
            }
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

    /**
     * The guarded counterpart of {@link #noSubmissionFailedForAnUnrelatedReason}, for races where
     * losing on a guard is the measured behaviour rather than noise.
     *
     * <p>A guarded loser ends in one of two ways, and both are correct: refused at endorsement when
     * the guarded registry moved before the proposal was simulated, invalidated at commit time when
     * it moved after. Everything else that stops a submission short of validation — a transport
     * failure, an ownership refusal, a malformed argument — is still a broken fixture rather than a
     * result, and still fails the scenario.
     */
    @Then("no concurrent submission should have failed for a reason other than its guard")
    public void noSubmissionFailedForAReasonOtherThanItsGuard() {
        requireConcurrentResults();
        final List<TransactionResult> unrelated =
                concurrentResults.stream()
                        .filter(r -> !refusedByItsGuard(r))
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
                        "A guarded race is only evidence if every submission either reached"
                                + " validation or was refused by its own guard. "
                                + unrelated.size()
                                + " ended some other way. "
                                + diagnostics());
    }

    @Then("{int} concurrent submission(s) should have been refused by its guard")
    public void nSubmissionsRefusedByTheirGuard(final int expected) {
        requireConcurrentResults();
        final long refused = concurrentResults.stream().filter(this::refusedByItsGuard).count();
        assertEquals(
                (long) expected,
                refused,
                () ->
                        "Expected "
                                + expected
                                + " of the "
                                + concurrentResults.size()
                                + " concurrent submissions to lose on the guard it carried, but "
                                + refused
                                + " did. "
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

    /**
     * Counts how the independent registries ended up without naming which one won.
     *
     * <p>Both issuers acted on the same information, so which of them commits is the ledger's
     * choice and not a property under test; an assertion that named a winner would be asserting the
     * ordering service's timing. What is under test is that one registry moved and the other did
     * not.
     */
    @Then(
            "exactly {int} of the independent registries should be at version {long} and the"
                    + " other(s) at version {long}")
    public void exactlyNRegistriesAdvanced(
            final int expected, final long advanced, final long untouched) {
        final List<String> handles = registry.independentRegistry().provisionedHandles();
        if (handles.isEmpty()) {
            fail("No independent registries were provisioned; this step has nothing to count.");
        }
        final Map<String, Long> versions = new LinkedHashMap<>();
        handles.forEach(h -> versions.put(h, currentVersion(h)));
        final long advancedCount = versions.values().stream().filter(v -> v == advanced).count();
        final boolean restUntouched =
                versions.values().stream().allMatch(v -> v == advanced || v == untouched);
        assertTrue(
                advancedCount == expected && restUntouched,
                () ->
                        "Expected exactly "
                                + expected
                                + " of the independent registries at version "
                                + advanced
                                + " and every other one at version "
                                + untouched
                                + ", but they read "
                                + versions
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

    /**
     * Whether one submission lost on the guard list it carried, in either of the two ways it can.
     *
     * <p>Refused at endorsement, the chaincode names the registry and the two versions, and that
     * message is the only thing that distinguishes the refusal from any other the chaincode could
     * raise. Invalidated at commit, there is no message at all: the evidence is the validation
     * code, and the read set that produced it exists only because the transaction read the guarded
     * entry itself.
     *
     * <p>Both require the submission to have gone over the guarded path — an unguarded update
     * losing the same race says nothing about a guard.
     */
    private boolean refusedByItsGuard(final TransactionResult result) {
        if (!GUARDED_UPDATE_TX.equals(result.txName())) {
            return false;
        }
        if (result.outcome() == TransactionOutcome.MVCC_CONFLICT) {
            return true;
        }
        return result.outcome() == TransactionOutcome.ENDORSEMENT_REJECTED
                && result.failure() != null
                && result.failure().getMessage() != null
                && result.failure().getMessage().contains(GUARD_REFUSAL);
    }

    /**
     * Builds one submission's guard list from what its own issuer observed earlier in the scenario.
     *
     * <p>Expected versions are observed, never written into the feature file: the guard's claim is
     * about a version somebody actually read, and a literal would keep passing for the wrong reason
     * the moment the fixture gained a step. A guard the issuer never looked at is therefore a fault
     * in the scenario and says so — substituting a default would guard against a version nobody
     * checked and still go green.
     *
     * <p>The list is rendered exactly once, here; that one string is what the token is minted over
     * and what is submitted.
     */
    private GuardsJson guardListOf(final String handle, final String guardCell) {
        final GuardsJson.Builder guards = GuardsJson.builder();
        for (final String guard : guardCell.split(",")) {
            final String target = guard.trim();
            final String observation = handle + " read " + target;
            final Long observed = observations.get(observation);
            if (observed == null) {
                fail(
                        "The submission of '"
                                + handle
                                + "' guards '"
                                + target
                                + "' at the version its issuer observed, but no observation step"
                                + " recorded '"
                                + observation
                                + "'. "
                                + diagnostics());
            }
            guards.guard(revRegDefIdOf(target), observed);
        }
        return guards.build();
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
