package dev.brickfolio.jsleeper.internal;

import dev.brickfolio.jsleeper.RateLimit;
import dev.brickfolio.jsleeper.error.SleeperRateLimitException;
import dev.brickfolio.jsleeper.error.SleeperTransportException;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Token bucket shared by every call on one client. Tokens refill continuously at the configured
 * rate up to the burst size.
 *
 * <p>A caller that finds no token <em>reserves</em> one (the balance goes negative) and sleeps
 * outside the lock until its reservation matures, so waiters queue in order instead of waking at
 * once. A {@link ReentrantLock} guards the state, never {@code synchronized}, so virtual threads
 * are not pinned.
 */
public final class RateLimiter {

    private static final System.Logger LOG = System.getLogger("dev.brickfolio.jsleeper");
    private static final Duration LONG_WAIT = Duration.ofSeconds(5);
    private static final double NANOS_PER_MILLI = 1_000_000.0;
    private static final double MILLIS_PER_MINUTE = 60_000.0;

    private final boolean disabled;
    private final double burst;
    private final double tokensPerMilli;
    private final long maxWaitNanos;
    private final Clock clock;
    private final Sleeper sleeper;
    private final ReentrantLock lock = new ReentrantLock();

    // Guarded by lock. May be negative while reservations are outstanding.
    private double tokens;
    private long lastRefillMillis;

    /** Creates a limiter that starts with a full bucket. */
    public RateLimiter(RateLimit limit, Clock clock, Sleeper sleeper) {
        Objects.requireNonNull(limit, "limit");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.disabled = limit.isDisabled();
        this.burst = limit.burst();
        this.tokensPerMilli = limit.perMinute() / MILLIS_PER_MINUTE;
        this.maxWaitNanos = limit.maxWait().toNanos();
        this.tokens = burst;
        this.lastRefillMillis = clock.millis();
    }

    /**
     * Takes one token, sleeping if none is free.
     *
     * @param method HTTP method, for the exception if the wait is too long
     * @param path request path, for the same purpose
     * @throws SleeperRateLimitException (client side) if the wait would exceed the maximum; nothing
     *     is sent and no token is taken
     * @throws SleeperTransportException if the thread is interrupted while waiting; its flag is
     *     restored. The reserved token is not returned.
     */
    public void acquire(String method, String path) {
        if (disabled) {
            return;
        }
        long waitNanos = reserve(method, path);
        if (waitNanos > 0) {
            if (waitNanos > LONG_WAIT.toNanos()) {
                LOG.log(Level.WARNING, "Client-side rate limit is delaying a request by {0} ms",
                        waitNanos / 1_000_000);
            }
            try {
                sleeper.sleep(Duration.ofNanos(waitNanos));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SleeperTransportException(method, path, e);
            }
        }
    }

    /** Takes a token under the lock and returns how long the caller must wait for it. */
    private long reserve(String method, String path) {
        lock.lock();
        try {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return 0;
            }
            long waitNanos = (long) Math.ceil((1.0 - tokens) / tokensPerMilli * NANOS_PER_MILLI);
            if (waitNanos > maxWaitNanos) {
                throw SleeperRateLimitException.clientSide(method, path);
            }
            tokens -= 1.0;
            return waitNanos;
        } finally {
            lock.unlock();
        }
    }

    private void refill() {
        long now = clock.millis();
        long elapsed = now - lastRefillMillis;
        if (elapsed > 0) {
            tokens = Math.min(burst, tokens + elapsed * tokensPerMilli);
            lastRefillMillis = now;
        }
    }
}
