package com.example.vdr.e2e.support;

import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.hyperledger.fabric.client.CommitException;
import org.hyperledger.fabric.client.CommitStatusException;
import org.hyperledger.fabric.client.EndorseException;
import org.hyperledger.fabric.client.GatewayException;
import org.hyperledger.fabric.client.GatewayRuntimeException;
import org.hyperledger.fabric.client.Status;
import org.hyperledger.fabric.client.SubmitException;
import org.hyperledger.fabric.client.SubmittedTransaction;
import org.hyperledger.fabric.client.Transaction;
import org.hyperledger.fabric.client.TransactionException;
import org.hyperledger.fabric.protos.peer.TxValidationCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralises submit/evaluate semantics: error flattening, exception capture into the {@link
 * ScenarioContext}, and JWT-argument appending. Step classes never call {@code
 * contract.submitTransaction} directly.
 *
 * <p>Two submit surfaces coexist. {@link #submit} keeps the original fire-and-capture contract the
 * lifecycle scenarios are written against: void, never throwing, last failure readable from the
 * context. The outcome-returning surface ({@link #submitSignedForOutcome}, {@link #submitTogether})
 * additionally classifies what happened — endorsement refusal, commit-time MVCC invalidation and
 * transport failure are three different events, and a concurrency assertion that cannot separate
 * them is unreadable.
 */
public class TransactionExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionExecutor.class);
    private static final long CONCURRENT_AWAIT_SECONDS = 120L;

    private final FabricConnectionManager connection;
    private final ScenarioContext context;

    private RetryPolicy retryPolicy = RetryPolicy.defaultPolicy();

    public TransactionExecutor(
            final FabricConnectionManager connection, final ScenarioContext context) {
        this.connection = connection;
        this.context = context;
    }

    /** Submits an unprotected transaction (no JWT). Captures any failure into the context. */
    public void submit(final String txName, final String... args) {
        context.setLastTransactionException(null);
        context.setLastTransactionResult(null);
        try {
            connection.contract().submitTransaction(txName, args);
            LOGGER.info("Transaction {} submitted successfully.", txName);
            context.setLastTransactionResult(TransactionResult.committed(txName, txName, 1));
        } catch (EndorseException e) {
            final IllegalStateException flattened = flatten(e);
            context.setLastTransactionException(flattened);
            context.setLastTransactionResult(classify(txName, txName, e, flattened, 1));
            LOGGER.error("{} endorsement failed: {}", txName, e.getMessage(), e);
        } catch (Exception e) {
            context.setLastTransactionException(e);
            context.setLastTransactionResult(classify(txName, txName, e, e, 1));
            LOGGER.error("{} submission failed.", txName, e);
        }
    }

    /**
     * Submits a JWT-protected transaction signed by {@code signerDid}, minting the scope-bound
     * one-time token from the exact method name and payload arguments that are submitted — the
     * token's transaction digest therefore matches the invocation by construction.
     */
    public void submitSigned(
            final String txName,
            final String signerDid,
            final KeyPair keyPair,
            final String... payloadArgs) {
        final String jwt =
                CryptoUtils.buildDidJwt(signerDid, keyPair, txName, Arrays.asList(payloadArgs));
        submitWithJwt(txName, jwt, payloadArgs);
    }

    /**
     * Submits a JWT-protected transaction with a caller-supplied token, appending {@code jwt} as
     * the final argument. Used for negative paths exercising malformed or mis-issued tokens;
     * regular flows should use {@link #submitSigned}.
     */
    public void submitWithJwt(final String txName, final String jwt, final String... payloadArgs) {
        submit(txName, withJwt(jwt, payloadArgs));
    }

    /** Evaluates a read-only transaction and returns the UTF-8 response, throwing on failure. */
    public String evaluate(final String txName, final String... args) throws Exception {
        final byte[] result = connection.contract().evaluateTransaction(txName, args);
        return new String(result, StandardCharsets.UTF_8);
    }

    /** The backoff policy the outcome-returning submits run under. */
    public RetryPolicy retryPolicy() {
        return retryPolicy;
    }

    /**
     * Replaces the backoff policy. Concurrency scenarios set {@link RetryPolicy#none()}: a retried
     * loser would quietly turn a measured conflict into a success.
     */
    public void setRetryPolicy(final RetryPolicy policy) {
        this.retryPolicy = policy;
    }

    /**
     * Submits a signed transaction and returns what it did, retrying under the active policy.
     *
     * <p>The token is minted inside the retry loop: the {@code jti} is one-time, so a repeat of the
     * same token would be rejected as a replay rather than retried.
     *
     * @param label short scenario-facing name quoted back in assertion messages
     */
    public TransactionResult submitSignedForOutcome(
            final String label,
            final String txName,
            final String signerDid,
            final KeyPair keyPair,
            final String... payloadArgs) {
        return submitForOutcome(
                label,
                txName,
                () ->
                        withJwt(
                                CryptoUtils.buildDidJwt(
                                        signerDid, keyPair, txName, Arrays.asList(payloadArgs)),
                                payloadArgs));
    }

    /**
     * Races several signed transactions against each other and reports what each one did.
     *
     * <p>Every proposal is endorsed before any of them is submitted. Endorsement simulates against
     * the currently committed state, so all of them read the same key versions; the submissions are
     * then released together from a common start gate. Any read-write overlap therefore has to be
     * resolved by the ledger at validation time rather than by the client's timing.
     *
     * <p>Deliberately not retried: repeating a losing attempt is exactly what would hide the
     * behaviour being measured. The active policy must be {@link RetryPolicy#none()}.
     */
    public List<TransactionResult> submitTogether(final List<ConcurrentSubmission> submissions) {
        if (retryPolicy.isEnabled()) {
            throw new IllegalStateException(
                    "Concurrent submission requires retrying to be switched off, but the active"
                            + " policy is "
                            + retryPolicy
                            + "; disable it in the scenario before racing transactions.");
        }

        final List<EndorsedProposal> endorsed = new ArrayList<>();
        for (final ConcurrentSubmission submission : submissions) {
            endorsed.add(endorse(submission));
        }
        LOGGER.info(
                "Endorsed {} proposal(s) against the same committed state; releasing them"
                        + " together.",
                endorsed.size());
        return releaseTogether(endorsed);
    }

    /** Runs one submit with retries, returning the outcome of the final attempt. */
    private TransactionResult submitForOutcome(
            final String label, final String txName, final Supplier<String[]> argsSupplier) {
        TransactionResult result = null;
        for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
            result = submitOnce(label, txName, argsSupplier.get(), attempt);
            if (!retryPolicy.shouldRetry(result.outcome(), attempt)) {
                break;
            }
            final long backoff = retryPolicy.backoffMillis(attempt);
            LOGGER.warn(
                    "Attempt {}/{} of {} ended as {}; retrying in {}ms.",
                    attempt,
                    retryPolicy.maxAttempts(),
                    txName,
                    result.outcome(),
                    backoff);
            sleep(backoff);
        }
        context.setLastTransactionResult(result);
        context.setLastTransactionException(result == null ? null : result.failure());
        return result;
    }

    private TransactionResult submitOnce(
            final String label, final String txName, final String[] args, final int attempt) {
        try {
            connection.contract().submitTransaction(txName, args);
            LOGGER.info("Transaction {} ({}) committed on attempt {}.", txName, label, attempt);
            return TransactionResult.committed(label, txName, attempt);
        } catch (EndorseException e) {
            return classify(label, txName, e, flatten(e), attempt);
        } catch (Exception e) {
            return classify(label, txName, e, e, attempt);
        }
    }

    /** Builds and endorses one proposal without submitting it. */
    private EndorsedProposal endorse(final ConcurrentSubmission submission) {
        final String[] args =
                withJwt(
                        CryptoUtils.buildDidJwt(
                                submission.signerDid(),
                                submission.keyPair(),
                                submission.txName(),
                                Arrays.asList(submission.payloadArgs())),
                        submission.payloadArgs());
        try {
            final Transaction transaction =
                    connection
                            .contract()
                            .newProposal(submission.txName())
                            .addArguments(args)
                            .build()
                            .endorse();
            return new EndorsedProposal(submission, transaction, null, null);
        } catch (EndorseException e) {
            return new EndorsedProposal(submission, null, e, flatten(e));
        } catch (Exception e) {
            return new EndorsedProposal(submission, null, e, e);
        }
    }

    /** Releases every endorsed proposal from one start gate and collects the commit statuses. */
    private List<TransactionResult> releaseTogether(final List<EndorsedProposal> endorsed) {
        final ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, endorsed.size()));
        final CountDownLatch startGate = new CountDownLatch(1);
        try {
            final List<Future<TransactionResult>> futures = new ArrayList<>();
            for (final EndorsedProposal proposal : endorsed) {
                futures.add(
                        pool.submit(
                                () -> {
                                    startGate.await();
                                    return submitEndorsed(proposal);
                                }));
            }
            startGate.countDown();

            final List<TransactionResult> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                results.add(awaitResult(futures.get(i), endorsed.get(i)));
            }
            results.forEach(r -> LOGGER.info("Concurrent submission result: {}", r.describe()));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private TransactionResult submitEndorsed(final EndorsedProposal proposal) {
        final ConcurrentSubmission submission = proposal.submission();
        if (proposal.failure() != null) {
            return classify(
                    submission.label(),
                    submission.txName(),
                    proposal.rawFailure(),
                    proposal.failure(),
                    1);
        }
        try {
            final SubmittedTransaction submitted = proposal.transaction().submitAsync();
            final Status status = submitted.getStatus();
            return TransactionResult.fromStatus(submission.label(), submission.txName(), status, 1);
        } catch (Exception e) {
            return classify(submission.label(), submission.txName(), e, e, 1);
        }
    }

    private static TransactionResult awaitResult(
            final Future<TransactionResult> future, final EndorsedProposal proposal) {
        try {
            return future.get(CONCURRENT_AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return classify(
                    proposal.submission().label(),
                    proposal.submission().txName(),
                    e,
                    new IllegalStateException("Interrupted while awaiting commit status", e),
                    1);
        } catch (ExecutionException e) {
            final Throwable cause = e.getCause();
            final Exception unwrapped =
                    cause instanceof Exception ? (Exception) cause : new IllegalStateException(e);
            return classify(
                    proposal.submission().label(),
                    proposal.submission().txName(),
                    unwrapped,
                    unwrapped,
                    1);
        } catch (Exception e) {
            return classify(proposal.submission().label(), proposal.submission().txName(), e, e, 1);
        }
    }

    /**
     * Separates the three failure modes the Fabric client funnels through one exception channel.
     *
     * <p>{@code raw} drives the classification, {@code stored} is what an assertion reads back: for
     * a rejected endorsement that is the flattened detail message, everywhere else the original.
     */
    private static TransactionResult classify(
            final String label,
            final String txName,
            final Exception raw,
            final Exception stored,
            final int attempt) {
        if (raw instanceof CommitException) {
            final CommitException e = (CommitException) raw;
            final TxValidationCode code = e.getCode();
            return TransactionResult.failed(
                    label,
                    txName,
                    e.getTransactionId(),
                    TransactionResult.outcomeForValidationCode(code),
                    code,
                    stored,
                    attempt);
        }
        if (raw instanceof EndorseException) {
            final EndorseException e = (EndorseException) raw;
            return TransactionResult.failed(
                    label,
                    txName,
                    e.getTransactionId(),
                    isTransportFailure(e)
                            ? TransactionOutcome.NETWORK_FAILURE
                            : TransactionOutcome.ENDORSEMENT_REJECTED,
                    null,
                    stored,
                    attempt);
        }
        if (raw instanceof SubmitException) {
            // The orderer never accepted the envelope, so nothing was ordered.
            return TransactionResult.failed(
                    label,
                    txName,
                    ((TransactionException) raw).getTransactionId(),
                    TransactionOutcome.NETWORK_FAILURE,
                    null,
                    stored,
                    attempt);
        }
        if (raw instanceof CommitStatusException) {
            // Ordered, but the outcome could not be read back: not safe to call either way.
            return TransactionResult.failed(
                    label,
                    txName,
                    ((TransactionException) raw).getTransactionId(),
                    TransactionOutcome.COMMIT_STATUS_UNKNOWN,
                    null,
                    stored,
                    attempt);
        }
        if (raw instanceof StatusRuntimeException
                || raw instanceof GatewayRuntimeException
                || raw.getCause() instanceof StatusRuntimeException) {
            return TransactionResult.failed(
                    label, txName, null, TransactionOutcome.NETWORK_FAILURE, null, stored, attempt);
        }
        return TransactionResult.failed(
                label, txName, null, TransactionOutcome.OTHER_FAILURE, null, stored, attempt);
    }

    /**
     * Distinguishes a peer that could not be reached from a chaincode that said no. Only the gRPC
     * transport codes count as unreachable; a chaincode refusal arrives as ABORTED with error
     * details attached.
     */
    private static boolean isTransportFailure(final GatewayException e) {
        final io.grpc.Status.Code code = e.getStatus().getCode();
        return code == io.grpc.Status.Code.UNAVAILABLE
                || code == io.grpc.Status.Code.DEADLINE_EXCEEDED;
    }

    private static String[] withJwt(final String jwt, final String... payloadArgs) {
        final String[] finalArgs = Arrays.copyOf(payloadArgs, payloadArgs.length + 1);
        finalArgs[payloadArgs.length] = jwt;
        return finalArgs;
    }

    private static void sleep(final long millis) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static IllegalStateException flatten(final EndorseException e) {
        final String details =
                e.getDetails().stream().map(d -> d.getMessage()).collect(Collectors.joining("; "));
        return new IllegalStateException(details, e);
    }

    /** An endorsed-but-not-yet-submitted proposal, or the refusal that stopped it. */
    private static final class EndorsedProposal {

        private final ConcurrentSubmission submission;
        private final Transaction transaction;
        private final Exception rawFailure;
        private final Exception storedFailure;

        EndorsedProposal(
                final ConcurrentSubmission submission,
                final Transaction transaction,
                final Exception rawFailure,
                final Exception storedFailure) {
            this.submission = submission;
            this.transaction = transaction;
            this.rawFailure = rawFailure;
            this.storedFailure = storedFailure;
        }

        ConcurrentSubmission submission() {
            return submission;
        }

        Transaction transaction() {
            return transaction;
        }

        /** The exception classification looks at; the flattened twin is what assertions read. */
        Exception rawFailure() {
            return rawFailure;
        }

        Exception failure() {
            return storedFailure;
        }
    }
}
