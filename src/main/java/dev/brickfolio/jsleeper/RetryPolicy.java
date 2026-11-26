package dev.brickfolio.jsleeper;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * When and how long to wait before retrying a failed request. Immutable and thread-safe.
 *
 * <p>Every endpoint is a GET, so every request is safe to retry. The wait before retry
 * {@code n} (1-based) is a random value between zero and
 * {@code min(maxBackoff, initialBackoff * 2^(n-1))} ("full jitter"), so concurrent callers do not
 * retry in lockstep.
 *
 * <p>If Sleeper sends a {@code Retry-After} header (integer seconds or an HTTP date) on a
 * retryable response, that delay is honoured exactly. If it asks for more than 60 seconds, the
 * client does not wait: it throws the response's exception immediately.
 */
public final class RetryPolicy {

    /** {@code Retry-After} delays above this make the client give up instead of sleeping. */
    public static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(60);

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(500);
    private static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(8);
    private static final Set<Integer> DEFAULT_RETRY_STATUSES = Set.of(429, 500, 502, 503, 504);
    private static final int BACKOFF_MULTIPLIER = 2;
    private static final int MAX_SAFE_SHIFT = 30;

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Set<Integer> retryOnStatuses;
    private final boolean retryOnTransportErrors;

    private RetryPolicy(Builder b) {
        this.maxAttempts = b.maxAttempts;
        this.initialBackoff = b.initialBackoff;
        this.maxBackoff = b.maxBackoff;
        this.retryOnStatuses = Set.copyOf(b.retryOnStatuses);
        this.retryOnTransportErrors = b.retryOnTransportErrors;
    }

    /**
     * Returns the default policy: 3 attempts in total, backoff from 500 ms doubling up to 8 s, retry
     * on 429, 500, 502, 503, 504 and transport errors (I/O failures and timeouts).
     */
    public static RetryPolicy defaults() {
        return builder().build();
    }

    /** Returns a policy that never retries (a single attempt). */
    public static RetryPolicy none() {
        return builder().maxAttempts(1).build();
    }

    /** Returns a builder starting from the defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the total number of attempts, including the first. */
    public int maxAttempts() {
        return maxAttempts;
    }

    /** Returns the backoff ceiling before the first retry. */
    public Duration initialBackoff() {
        return initialBackoff;
    }

    /** Returns the largest backoff ceiling. */
    public Duration maxBackoff() {
        return maxBackoff;
    }

    /** Returns the HTTP statuses that are retried. */
    public Set<Integer> retryOnStatuses() {
        return retryOnStatuses;
    }

    /** Returns whether transport errors (I/O failures, timeouts) are retried. */
    public boolean retryOnTransportErrors() {
        return retryOnTransportErrors;
    }

    /**
     * Returns the upper bound of the random wait before retry number {@code retryIndex}:
     * {@code min(maxBackoff, initialBackoff * 2^(retryIndex-1))}.
     *
     * @param retryIndex 1 for the first retry
     * @throws IllegalArgumentException if {@code retryIndex} is below 1
     */
    public Duration backoffCeiling(int retryIndex) {
        if (retryIndex < 1) {
            throw new IllegalArgumentException("retryIndex must be at least 1: " + retryIndex);
        }
        int shift = retryIndex - 1;
        if (shift > MAX_SAFE_SHIFT) {
            return maxBackoff;
        }
        try {
            Duration scaled = initialBackoff.multipliedBy((long) Math.pow(BACKOFF_MULTIPLIER, shift));
            return scaled.compareTo(maxBackoff) < 0 ? scaled : maxBackoff;
        } catch (ArithmeticException overflow) {
            return maxBackoff;
        }
    }

    /** Builder for {@link RetryPolicy}. */
    public static final class Builder {
        private int maxAttempts = DEFAULT_MAX_ATTEMPTS;
        private Duration initialBackoff = DEFAULT_INITIAL_BACKOFF;
        private Duration maxBackoff = DEFAULT_MAX_BACKOFF;
        private Set<Integer> retryOnStatuses = DEFAULT_RETRY_STATUSES;
        private boolean retryOnTransportErrors = true;

        private Builder() {}

        /** Sets the total number of attempts, including the first; at least 1. */
        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        /** Sets the backoff ceiling for the first retry; must be positive. */
        public Builder initialBackoff(Duration initialBackoff) {
            this.initialBackoff = Objects.requireNonNull(initialBackoff, "initialBackoff");
            return this;
        }

        /** Sets the largest backoff ceiling; must not be below the initial backoff. */
        public Builder maxBackoff(Duration maxBackoff) {
            this.maxBackoff = Objects.requireNonNull(maxBackoff, "maxBackoff");
            return this;
        }

        /** Replaces the set of HTTP statuses that are retried. */
        public Builder retryOn(Set<Integer> statuses) {
            this.retryOnStatuses = Set.copyOf(Objects.requireNonNull(statuses, "statuses"));
            return this;
        }

        /** Sets whether transport errors (I/O failures, timeouts) are retried. */
        public Builder retryOnTransportErrors(boolean retryOnTransportErrors) {
            this.retryOnTransportErrors = retryOnTransportErrors;
            return this;
        }

        /**
         * Builds the policy.
         *
         * @throws IllegalArgumentException if {@code maxAttempts} is below 1, the initial backoff
         *     is not positive, or the maximum backoff is below the initial backoff
         */
        public RetryPolicy build() {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be at least 1: " + maxAttempts);
            }
            if (initialBackoff.isZero() || initialBackoff.isNegative()) {
                throw new IllegalArgumentException("initialBackoff must be positive: " + initialBackoff);
            }
            if (maxBackoff.compareTo(initialBackoff) < 0) {
                throw new IllegalArgumentException(
                        "maxBackoff " + maxBackoff + " must not be below initialBackoff " + initialBackoff);
            }
            return new RetryPolicy(this);
        }
    }
}
