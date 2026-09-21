package com.example.vdr.e2e.support;

/**
 * Bounded exponential backoff for submit attempts that provably left the ledger unchanged.
 *
 * <p>Only {@link TransactionOutcome#isRetryable()} outcomes are repeated: a rejected endorsement is
 * deterministic and repeating it only multiplies the same refusal, while an unreadable commit
 * status may already be in a block and must never be resubmitted.
 *
 * <p>Concurrency measurements must run with {@link #none()}: retrying the loser of a race hides the
 * very conflict the scenario exists to observe.
 */
public final class RetryPolicy {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final long DEFAULT_INITIAL_BACKOFF_MILLIS = 200L;
    private static final double DEFAULT_MULTIPLIER = 2.0d;

    private final int maxAttempts;
    private final long initialBackoffMillis;
    private final double multiplier;

    private RetryPolicy(
            final int maxAttempts, final long initialBackoffMillis, final double multiplier) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoffMillis = initialBackoffMillis;
        this.multiplier = multiplier;
    }

    /** No repetition: exactly one attempt, whatever happens. */
    public static RetryPolicy none() {
        return new RetryPolicy(1, 0L, 1.0d);
    }

    /** Three attempts with 200 ms doubling backoff — the policy the sequential scenarios run on. */
    public static RetryPolicy defaultPolicy() {
        return new RetryPolicy(
                DEFAULT_MAX_ATTEMPTS, DEFAULT_INITIAL_BACKOFF_MILLIS, DEFAULT_MULTIPLIER);
    }

    public static RetryPolicy of(
            final int maxAttempts, final long initialBackoffMillis, final double multiplier) {
        return new RetryPolicy(maxAttempts, initialBackoffMillis, multiplier);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public boolean isEnabled() {
        return maxAttempts > 1;
    }

    /**
     * Returns whether another attempt should follow.
     *
     * @param outcome outcome of the attempt that just finished
     * @param completedAttempts number of attempts made so far, 1-based
     */
    public boolean shouldRetry(final TransactionOutcome outcome, final int completedAttempts) {
        return outcome.isRetryable() && completedAttempts < maxAttempts;
    }

    /**
     * Returns the pause before attempt {@code completedAttempts + 1}.
     *
     * @param completedAttempts number of attempts made so far, 1-based
     */
    public long backoffMillis(final int completedAttempts) {
        double backoff = initialBackoffMillis;
        for (int i = 1; i < completedAttempts; i++) {
            backoff *= multiplier;
        }
        return (long) backoff;
    }

    @Override
    public String toString() {
        return isEnabled()
                ? String.format(
                        "RetryPolicy[maxAttempts=%d, initialBackoff=%dms, multiplier=%.1f]",
                        maxAttempts, initialBackoffMillis, multiplier)
                : "RetryPolicy[disabled]";
    }
}
