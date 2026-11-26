package dev.brickfolio.jsleeper;

import java.time.Duration;
import java.util.Objects;

/**
 * Client-side request rate limit, shared by all calls made through one {@link SleeperClient}.
 * Immutable: each {@code burst} or {@code maxWait} call returns a new instance.
 *
 * <p>Sleeper asks callers to stay under 1000 calls a minute or risk an IP block. The limiter is a
 * token bucket: up to {@code burst} requests go out at once, then requests are spaced at
 * {@code perMinute} per minute. A request that would have to wait longer than {@code maxWait} is
 * not sent; it fails with a client-side {@link dev.brickfolio.jsleeper.error.SleeperRateLimitException}.
 *
 * <p>The limit is per client. Several clients in one JVM, or several processes on one IP, do not
 * share it.
 */
public final class RateLimit {

    private static final int DEFAULT_PER_MINUTE = 900;
    private static final int DEFAULT_BURST = 50;
    private static final Duration DEFAULT_MAX_WAIT = Duration.ofSeconds(30);
    private static final RateLimit DISABLED = new RateLimit(true, 1, 1, Duration.ZERO);

    private final boolean disabled;
    private final int perMinute;
    private final int burst;
    private final Duration maxWait;

    private RateLimit(boolean disabled, int perMinute, int burst, Duration maxWait) {
        this.disabled = disabled;
        this.perMinute = perMinute;
        this.burst = burst;
        this.maxWait = maxWait;
    }

    /**
     * Returns the default limit: 900 per minute, burst 50, wait at most 30 seconds.
     *
     * @return the default settings
     */
    public static RateLimit defaults() {
        return new RateLimit(false, DEFAULT_PER_MINUTE, DEFAULT_BURST, DEFAULT_MAX_WAIT);
    }

    /**
     * Returns a limit of {@code n} requests per minute. The burst defaults to 50, or {@code n} if
     * that is smaller, and the maximum wait to 30 seconds.
     *
     * @return a new limit
     * @param n requests per minute; at least 1
     * @throws IllegalArgumentException if {@code n} is below 1
     */
    public static RateLimit perMinute(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("perMinute must be at least 1: " + n);
        }
        return new RateLimit(false, n, Math.min(DEFAULT_BURST, n), DEFAULT_MAX_WAIT);
    }

    /**
     * Returns a limit that never delays or rejects a request.
     *
     * @return a limit that never delays or rejects
     */
    public static RateLimit disabled() {
        return DISABLED;
    }

    /**
     * Returns a copy with a different burst size.
     *
     * @return a new limit with the change applied
     * @param burst requests allowed at once; at least 1
     * @throws IllegalArgumentException if {@code burst} is below 1
     */
    public RateLimit burst(int burst) {
        if (burst < 1) {
            throw new IllegalArgumentException("burst must be at least 1: " + burst);
        }
        return new RateLimit(disabled, perMinute, burst, maxWait);
    }

    /**
     * Returns a copy with a different longest wait. A zero wait means requests that cannot go
     * out immediately are rejected.
     *
     * @return a new limit with the change applied
     * @param maxWait the longest a request waits for a token; not negative
     * @throws IllegalArgumentException if {@code maxWait} is negative
     */
    public RateLimit maxWait(Duration maxWait) {
        Objects.requireNonNull(maxWait, "maxWait");
        if (maxWait.isNegative()) {
            throw new IllegalArgumentException("maxWait must not be negative: " + maxWait);
        }
        return new RateLimit(disabled, perMinute, burst, maxWait);
    }

    /**
     * Returns true if this limit never delays or rejects.
     *
     * @return true if no limiting happens
     */
    public boolean isDisabled() {
        return disabled;
    }

    /**
     * Returns the sustained rate in requests per minute.
     *
     * @return the requests per minute
     */
    public int perMinute() {
        return perMinute;
    }

    /**
     * Returns the number of requests allowed at once.
     *
     * @return the burst size
     */
    public int burst() {
        return burst;
    }

    /**
     * Returns the longest a request waits for a token before failing.
     *
     * @return the longest wait
     */
    public Duration maxWait() {
        return maxWait;
    }
}
