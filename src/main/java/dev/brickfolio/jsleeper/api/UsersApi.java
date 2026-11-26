package dev.brickfolio.jsleeper.api;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import dev.brickfolio.jsleeper.internal.EmptyKind;
import dev.brickfolio.jsleeper.internal.Requester;
import dev.brickfolio.jsleeper.model.Draft;
import dev.brickfolio.jsleeper.model.League;
import dev.brickfolio.jsleeper.model.Sport;
import dev.brickfolio.jsleeper.model.User;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** User endpoints. Stateless and thread-safe. */
public final class UsersApi {

    private static final TypeFactory TYPES = TypeFactory.defaultInstance();
    private static final JavaType USER_TYPE = TYPES.constructType(User.class);
    private static final JavaType LEAGUE_LIST_TYPE =
            TYPES.constructCollectionType(List.class, League.class);

    private static final JavaType DRAFT_LIST_TYPE =
            TYPES.constructCollectionType(List.class, Draft.class);

    private final Requester requester;

    /** For use by {@link dev.brickfolio.jsleeper.SleeperClient} only. */
    @SuppressWarnings("exports")
    public UsersApi(Requester requester) {
        this.requester = Objects.requireNonNull(requester, "requester");
    }

    /**
     * Looks up a user by username or user ID ({@code /user/{usernameOrId}}).
     *
     * <p>Usernames can change, so store {@link User#userId()}. An unknown user gives an empty
     * Optional, not an exception.
     *
     * @throws IllegalArgumentException if the argument is null or blank
     */
    public Optional<User> get(String usernameOrId) {
        Args.requireId("usernameOrId", usernameOrId);
        return requester.get(List.of("user", usernameOrId), Map.of(), USER_TYPE,
                EmptyKind.OPTIONAL, null);
    }

    /**
     * Returns the leagues a user belongs to for a sport and season
     * ({@code /user/{userId}/leagues/{sport}/{season}}), or an empty list.
     *
     * <p>Use {@code SportState.leagueSeason()} for the current season. Sleeper cannot tell an
     * unknown user from a user with no leagues; both give an empty list.
     *
     * @throws IllegalArgumentException if {@code userId} is blank, {@code sport} is null or
     *     {@link Sport#UNKNOWN}, or {@code season} is not positive
     */
    public List<League> leagues(String userId, Sport sport, int season) {
        Args.requireId("userId", userId);
        Args.requireSport(sport);
        Args.requirePositive("season", season);
        return requester.get(
                List.of("user", userId, "leagues", sport.wireValue(), Integer.toString(season)),
                Map.of(), LEAGUE_LIST_TYPE, EmptyKind.LIST, null);
    }

    /**
     * Returns the drafts a user took part in for a sport and season
     * ({@code /user/{userId}/drafts/{sport}/{season}}), or an empty list. These entries leave
     * {@code slotToRosterId} empty.
     *
     * @throws IllegalArgumentException if {@code userId} is blank, {@code sport} is null or
     *     {@link Sport#UNKNOWN}, or {@code season} is not positive
     */
    public List<Draft> drafts(String userId, Sport sport, int season) {
        Args.requireId("userId", userId);
        Args.requireSport(sport);
        Args.requirePositive("season", season);
        return requester.get(
                List.of("user", userId, "drafts", sport.wireValue(), Integer.toString(season)),
                Map.of(), DRAFT_LIST_TYPE, EmptyKind.LIST, null);
    }
}
