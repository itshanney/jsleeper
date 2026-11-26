package dev.brickfolio.jsleeper.players;

import dev.brickfolio.jsleeper.SleeperClient;
import dev.brickfolio.jsleeper.model.Player;
import dev.brickfolio.jsleeper.model.Sport;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory, time-limited cache of all players of one sport, so the ~5 MB players endpoint is
 * fetched about once a day as Sleeper asks. Thread-safe.
 *
 * <p>Nothing is fetched until the first lookup. Once data exists, readers never block: when the
 * data is older than the TTL, one thread refreshes on its own thread while every other thread keeps
 * reading the previous data. If a refresh fails, the old data is kept, a warning is logged, and
 * the next attempt waits {@code min(ttl, 1 hour)}. With no data at all, a failure is rethrown and
 * the next call tries again.
 *
 * <pre>{@code
 * PlayerDirectory players = PlayerDirectory.builder(sleeper)
 *         .sport(Sport.NFL)
 *         .ttl(Duration.ofHours(24))
 *         .build();                       // no HTTP call yet
 * String name = players.get("4046").map(Player::fullName).orElse("4046");
 * }</pre>
 */
public final class PlayerDirectory {

    private static final System.Logger LOG = System.getLogger("dev.brickfolio.jsleeper");
    private static final Duration MAX_FAILURE_BACKOFF = Duration.ofHours(1);

    private final SleeperClient client;
    private final Sport sport;
    private final Duration ttl;
    private final Clock clock;
    private final ReentrantLock refreshLock = new ReentrantLock();

    /** Immutable view of the loaded data; replaced as a whole. */
    private record Snapshot(Map<String, Player> players, Instant loadedAt, Instant nextRefreshAt) {}

    private volatile Snapshot snapshot;

    private PlayerDirectory(SleeperClient client, Sport sport, Duration ttl) {
        this.client = client;
        this.sport = sport;
        this.ttl = ttl;
        this.clock = client.clock();
    }

    /** Returns a builder for a directory that loads through {@code client}. */
    public static Builder builder(SleeperClient client) {
        return new Builder(Objects.requireNonNull(client, "client"));
    }

    /**
     * Looks up a player by ID, loading or refreshing the data first if needed. Team defenses use
     * codes such as "DET".
     *
     * @return the player, or empty if the ID is unknown
     * @throws dev.brickfolio.jsleeper.error.SleeperException if the first load fails
     */
    public Optional<Player> get(String playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return Optional.ofNullable(current().players().get(playerId));
    }

    /**
     * Returns every player keyed by ID as an unmodifiable map, loading or refreshing first if
     * needed.
     *
     * @throws dev.brickfolio.jsleeper.error.SleeperException if the first load fails
     */
    public Map<String, Player> all() {
        return current().players();
    }

    /**
     * Fetches the players now, whatever the TTL. Blocks while another refresh is running. Unlike
     * the automatic refresh, a failure is always thrown (existing data is kept).
     *
     * @throws dev.brickfolio.jsleeper.error.SleeperException if the fetch fails
     */
    public void refresh() {
        refreshLock.lock();
        try {
            fetchAndPublish();
        } finally {
            refreshLock.unlock();
        }
    }

    /** Returns when the current data was loaded, or empty if nothing has been loaded yet. */
    public Optional<Instant> loadedAt() {
        Snapshot s = snapshot;
        return s == null ? Optional.empty() : Optional.of(s.loadedAt());
    }

    private Snapshot current() {
        Snapshot s = snapshot;
        if (s == null) {
            return loadFirstTime();
        }
        if (!clock.instant().isBefore(s.nextRefreshAt()) && refreshLock.tryLock()) {
            // Only the lock winner refreshes; everyone else returns the stale data without waiting.
            try {
                return refreshIfStillDue();
            } finally {
                refreshLock.unlock();
            }
        }
        return s;
    }

    private Snapshot loadFirstTime() {
        refreshLock.lock();
        try {
            Snapshot s = snapshot;
            return s != null ? s : fetchAndPublish();
        } finally {
            refreshLock.unlock();
        }
    }

    /** Runs under the lock. Keeps the old data and backs off if the fetch fails. */
    private Snapshot refreshIfStillDue() {
        Snapshot s = snapshot;
        if (clock.instant().isBefore(s.nextRefreshAt())) {
            return s;  // another thread refreshed between our check and taking the lock
        }
        try {
            return fetchAndPublish();
        } catch (RuntimeException failure) {
            Instant retryAt = clock.instant().plus(ttl.compareTo(MAX_FAILURE_BACKOFF) < 0 ? ttl : MAX_FAILURE_BACKOFF);
            LOG.log(Level.WARNING, "Refreshing the " + sport.wireValue()
                    + " player directory failed; serving stale data and retrying after " + retryAt, failure);
            Snapshot backedOff = new Snapshot(s.players(), s.loadedAt(), retryAt);
            snapshot = backedOff;
            return backedOff;
        }
    }

    /** Runs under the lock. */
    private Snapshot fetchAndPublish() {
        Map<String, Player> players = client.players().all(sport);
        Instant loadedAt = clock.instant();
        Snapshot fresh = new Snapshot(Collections.unmodifiableMap(players), loadedAt, loadedAt.plus(ttl));
        snapshot = fresh;
        return fresh;
    }

    /** Builder for {@link PlayerDirectory}. */
    public static final class Builder {
        private static final Duration DEFAULT_TTL = Duration.ofHours(24);

        private final SleeperClient client;
        private Sport sport = Sport.NFL;
        private Duration ttl = DEFAULT_TTL;

        private Builder(SleeperClient client) {
            this.client = client;
        }

        /** Sets the sport. Default NFL; must not be {@link Sport#UNKNOWN}. */
        public Builder sport(Sport sport) {
            Objects.requireNonNull(sport, "sport");
            if (sport == Sport.UNKNOWN) {
                throw new IllegalArgumentException("sport must be a known sport");
            }
            this.sport = sport;
            return this;
        }

        /**
         * Sets how long loaded data is served before a refresh. Default 24 hours; must be positive.
         * A TTL under one hour makes the client log its "fetched again within an hour" warning.
         */
        public Builder ttl(Duration ttl) {
            Objects.requireNonNull(ttl, "ttl");
            if (ttl.isZero() || ttl.isNegative()) {
                throw new IllegalArgumentException("ttl must be positive: " + ttl);
            }
            this.ttl = ttl;
            return this;
        }

        /** Builds the directory. Makes no HTTP call. */
        public PlayerDirectory build() {
            return new PlayerDirectory(client, sport, ttl);
        }
    }
}
