package dev.brickfolio.jsleeper;

import dev.brickfolio.jsleeper.api.DraftsApi;
import dev.brickfolio.jsleeper.api.LeaguesApi;
import dev.brickfolio.jsleeper.api.PlayersApi;
import dev.brickfolio.jsleeper.api.StateApi;
import dev.brickfolio.jsleeper.api.UsersApi;
import dev.brickfolio.jsleeper.internal.Requester;
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
        this.leagues = new LeaguesApi(requester);
        this.drafts = new DraftsApi(requester);
        this.players = new PlayersApi(requester);
        this.clock = clock;
    }

    /** Creates a client with default settings (production base URL, no network call). */
    public static SleeperClient create() {
        return builder().build();
    }

    /** Returns a builder for a customised client. */
    public static SleeperClientBuilder builder() {
        return new SleeperClientBuilder();
    }

    /** Sport state endpoint. */
    public StateApi state() {
        return state;
    }

    /** User endpoints. */
    public UsersApi users() {
        return users;
    }

    /** League endpoints. */
    public LeaguesApi leagues() {
        return leagues;
    }

    /** Draft endpoints. */
    public DraftsApi drafts() {
        return drafts;
    }

    /** Player endpoints. */
    public PlayersApi players() {
        return players;
    }

    /** Clock for rate limiting, backoff and cache TTLs in later increments. */
    Clock clock() {
        return clock;
    }
}
