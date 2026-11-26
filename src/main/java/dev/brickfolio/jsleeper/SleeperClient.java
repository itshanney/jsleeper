package dev.brickfolio.jsleeper;

import dev.brickfolio.jsleeper.api.DraftsApi;
import dev.brickfolio.jsleeper.api.LeaguesApi;
import dev.brickfolio.jsleeper.api.PlayersApi;
import dev.brickfolio.jsleeper.api.StateApi;
import dev.brickfolio.jsleeper.api.UsersApi;
import dev.brickfolio.jsleeper.internal.Requester;
import dev.brickfolio.jsleeper.internal.UnfilteredFetchWatch;
import java.time.Clock;

/**
 * Entry point of the library. Build once and share: instances are immutable and thread-safe.
 *
 * <pre>{@code
 * SleeperClient sleeper = SleeperClient.create();
 * SportState nfl = sleeper.state().get(Sport.NFL);
 * }</pre>
 */
public final class SleeperClient {

    private final StateApi state;
    private final UsersApi users;
    private final LeaguesApi leagues;
    private final DraftsApi drafts;
    private final PlayersApi players;
    private final Clock clock;

    SleeperClient(Requester requester, Clock clock) {
        this.state = new StateApi(requester);
        this.users = new UsersApi(requester);
        this.leagues = new LeaguesApi(requester, state);
        this.drafts = new DraftsApi(requester);
        this.players = new PlayersApi(requester, new UnfilteredFetchWatch(clock));
        this.clock = clock;
    }

    /**
     * Creates a client with default settings (production base URL, no network call).
     *
     * @return a client with default settings
     */
    public static SleeperClient create() {
        return builder().build();
    }

    /**
     * Returns a builder for a customised client.
     *
     * @return a new builder
     */
    public static SleeperClientBuilder builder() {
        return new SleeperClientBuilder();
    }

    /**
     * Sport state endpoint.
     *
     * @return the shared state facade
     */
    public StateApi state() {
        return state;
    }

    /**
     * User endpoints.
     *
     * @return the shared users facade
     */
    public UsersApi users() {
        return users;
    }

    /**
     * League endpoints.
     *
     * @return the shared leagues facade
     */
    public LeaguesApi leagues() {
        return leagues;
    }

    /**
     * Draft endpoints.
     *
     * @return the shared drafts facade
     */
    public DraftsApi drafts() {
        return drafts;
    }

    /**
     * Player endpoints.
     *
     * @return the shared players facade
     */
    public PlayersApi players() {
        return players;
    }

    /**
     * Returns the clock this client uses for rate limiting, retries and cache lifetimes.
     *
     * @return the clock
     */
    public Clock clock() {
        return clock;
    }
}
