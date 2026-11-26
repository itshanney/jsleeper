package dev.brickfolio.jsleeper.api;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import dev.brickfolio.jsleeper.internal.EmptyKind;
import dev.brickfolio.jsleeper.internal.Requester;
import dev.brickfolio.jsleeper.internal.UnfilteredFetchWatch;
import dev.brickfolio.jsleeper.model.Player;
import dev.brickfolio.jsleeper.model.Sport;
import dev.brickfolio.jsleeper.model.TrendType;
import dev.brickfolio.jsleeper.model.TrendingPlayer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Player endpoints. Stateless and thread-safe. */
public final class PlayersApi {

    // The unfiltered players map is about 5 MB, so it gets a longer timeout than other calls.
    private static final Duration UNFILTERED_TIMEOUT = Duration.ofSeconds(60);
    private static final TypeFactory TYPES = TypeFactory.defaultInstance();
    private static final JavaType PLAYER_MAP_TYPE =
            TYPES.constructMapType(Map.class, String.class, Player.class);
    private static final JavaType TRENDING_LIST_TYPE =
            TYPES.constructCollectionType(List.class, TrendingPlayer.class);

    private final Requester requester;
    private final UnfilteredFetchWatch unfilteredWatch;

    /** For use by {@link dev.brickfolio.jsleeper.SleeperClient} only. */
    @SuppressWarnings("exports")
    public PlayersApi(Requester requester, UnfilteredFetchWatch unfilteredWatch) {
        this.requester = Objects.requireNonNull(requester, "requester");
        this.unfilteredWatch = Objects.requireNonNull(unfilteredWatch, "unfilteredWatch");
    }

    /**
     * Returns every player of a sport keyed by player ID ({@code /players/{sport}}). The response
     * is about 5 MB and uses a 60 second timeout.
     *
     * <p><b>Call this at most once a day.</b> Sleeper asks callers to cache it and may block
     * abusive clients. Cache the result yourself; a {@code PlayerDirectory} that does so is planned.
     *
     * @throws IllegalArgumentException if {@code sport} is null or {@link Sport#UNKNOWN}
     */
    public Map<String, Player> all(Sport sport) {
        Args.requireSport(sport);
        unfilteredWatch.onUnfilteredFetch();
        return requester.get("/players/{sport}", List.of("players", sport.wireValue()), Map.of(), PLAYER_MAP_TYPE,
                EmptyKind.MAP, UNFILTERED_TIMEOUT);
    }

    /**
     * Returns the players matching a filter. Filtered responses are much smaller and use the
     * default request timeout; an empty query is the same as {@link #all(Sport)}, including the
     * once-a-day guidance.
     *
     * @throws IllegalArgumentException if {@code sport} is null or {@link Sport#UNKNOWN}
     */
    public Map<String, Player> all(Sport sport, PlayerQuery query) {
        Args.requireSport(sport);
        Objects.requireNonNull(query, "query");
        if (query.isUnfiltered()) {
            unfilteredWatch.onUnfilteredFetch();
        }
        return requester.get("/players/{sport}", List.of("players", sport.wireValue()), query.toQueryParameters(),
                PLAYER_MAP_TYPE, EmptyKind.MAP, query.isUnfiltered() ? UNFILTERED_TIMEOUT : null);
    }

    /**
     * Returns trending adds or drops with Sleeper's defaults (last 24 hours, 25 results).
     * Attribute Sleeper wherever this data is shown.
     *
     * @throws IllegalArgumentException if {@code sport} is null or {@link Sport#UNKNOWN}
     */
    public List<TrendingPlayer> trending(Sport sport, TrendType type) {
        return trending(sport, type, TrendingQuery.builder().build());
    }

    /**
     * Returns trending adds or drops with a custom lookback and limit.
     *
     * @throws IllegalArgumentException if {@code sport} is null or {@link Sport#UNKNOWN}
     */
    public List<TrendingPlayer> trending(Sport sport, TrendType type, TrendingQuery query) {
        Args.requireSport(sport);
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(query, "query");
        return requester.get("/players/{sport}/trending/{type}",
                List.of("players", sport.wireValue(), "trending", type.wireValue()),
                query.toQueryParameters(), TRENDING_LIST_TYPE, EmptyKind.LIST, null);
    }
}
