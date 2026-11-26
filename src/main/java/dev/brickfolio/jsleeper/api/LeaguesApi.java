package dev.brickfolio.jsleeper.api;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import dev.brickfolio.jsleeper.internal.EmptyKind;
import dev.brickfolio.jsleeper.internal.Requester;
import dev.brickfolio.jsleeper.model.League;
import dev.brickfolio.jsleeper.model.LeagueUser;
import dev.brickfolio.jsleeper.model.Roster;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * League endpoints. Stateless and thread-safe.
 *
 * <p>Sleeper answers an unknown league ID with an empty result, so a mistyped ID looks the same
 * as a league with no rows.
 */
public final class LeaguesApi {

    private static final TypeFactory TYPES = TypeFactory.defaultInstance();
    private static final JavaType LEAGUE_TYPE = TYPES.constructType(League.class);
    private static final JavaType LEAGUE_USER_LIST_TYPE =
            TYPES.constructCollectionType(List.class, LeagueUser.class);

    private static final JavaType ROSTER_LIST_TYPE =
            TYPES.constructCollectionType(List.class, Roster.class);

    private final Requester requester;

    /** For use by {@link dev.brickfolio.jsleeper.SleeperClient} only. */
    @SuppressWarnings("exports")
    public LeaguesApi(Requester requester) {
        this.requester = Objects.requireNonNull(requester, "requester");
    }

    /**
     * Returns a league ({@code /league/{leagueId}}), or empty if it does not exist.
     *
     * @throws IllegalArgumentException if {@code leagueId} is null or blank
     */
    public Optional<League> get(String leagueId) {
        Args.requireId("leagueId", leagueId);
        return requester.get(List.of("league", leagueId), Map.of(), LEAGUE_TYPE,
                EmptyKind.OPTIONAL, null);
    }

    /**
     * Returns the members of a league ({@code /league/{leagueId}/users}), or an empty list.
     *
     * @throws IllegalArgumentException if {@code leagueId} is null or blank
     */
    public List<LeagueUser> users(String leagueId) {
        Args.requireId("leagueId", leagueId);
        return requester.get(List.of("league", leagueId, "users"), Map.of(), LEAGUE_USER_LIST_TYPE,
                EmptyKind.LIST, null);
    }

    /**
     * Returns the rosters of a league ({@code /league/{leagueId}/rosters}), or an empty list.
     *
     * @throws IllegalArgumentException if {@code leagueId} is null or blank
     */
    public List<Roster> rosters(String leagueId) {
        Args.requireId("leagueId", leagueId);
        return requester.get(List.of("league", leagueId, "rosters"), Map.of(), ROSTER_LIST_TYPE,
                EmptyKind.LIST, null);
    }
}
