package dev.brickfolio.jsleeper.internal;

import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Warns, once per client, when the full players map is fetched again within an hour. Sleeper asks
 * for at most one fetch a day.
 */
public final class UnfilteredFetchWatch {

    private static final Duration TOO_SOON = Duration.ofHours(1);
    private static final System.Logger LOG = System.getLogger("dev.brickfolio.jsleeper");
    static final String MESSAGE = "The full players list was fetched again within an hour. Sleeper asks"
            + " for at most one fetch per day; cache the result, for example with PlayerDirectory.";

    private final Clock clock;
    private final Consumer<String> warningSink;
    private final AtomicReference<Instant> lastFetch = new AtomicReference<>();
    private final AtomicBoolean warned = new AtomicBoolean();

    /** Creates a watch that logs its warning at WARNING level. */
    public UnfilteredFetchWatch(Clock clock) {
        this(clock, message -> LOG.log(Level.WARNING, message));
    }

    /** Creates a watch with a custom warning sink. */
    public UnfilteredFetchWatch(Clock clock, Consumer<String> warningSink) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
    }

    /** Records an unfiltered fetch and warns if the previous one was under an hour ago. */
    public void onUnfilteredFetch() {
        Instant now = clock.instant();
        Instant previous = lastFetch.getAndSet(now);
        if (previous != null && Duration.between(previous, now).compareTo(TOO_SOON) < 0
                && warned.compareAndSet(false, true)) {
            warningSink.accept(MESSAGE);
        }
    }
}
