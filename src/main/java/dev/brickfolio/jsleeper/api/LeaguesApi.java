package dev.brickfolio.jsleeper.api;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import dev.brickfolio.jsleeper.internal.EmptyKind;
import dev.brickfolio.jsleeper.internal.Requester;
import dev.brickfolio.jsleeper.model.Draft;
import dev.brickfolio.jsleeper.model.League;
import dev.brickfolio.jsleeper.model.LeagueUser;
import dev.brickfolio.jsleeper.model.Matchup;
import dev.brickfolio.jsleeper.model.Roster;
import dev.brickfolio.jsleeper.model.TradedPick;
import dev.brickfolio.jsleeper.model.Transaction;
import dev.brickfolio.jsleeper.model.bracket.BracketMatch;
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

    private static final JavaType MATCHUP_LIST_TYPE =
            TYPES.constructCollectionType(List.class, Matchup.class);

    private static final JavaType BRACKET_LIST_TYPE =
            TYPES.constructCollectionType(List.class, BracketMatch.class);

    private static final JavaType TRANSACTION_LIST_TYPE =
            TYPES.constructCollectionType(List.class, Transaction.class);
    private static final JavaType TRADED_PICK_LIST_TYPE =
            TYPES.constructCollectionType(List.class, TradedPick.class);

    private static final JavaType DRAFT_LIST_TYPE =
            TYPES.constructCollectionType(List.class, Draft.class);

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

    /**
     * Returns every team's matchup row for a week ({@code /league/{leagueId}/matchups/{week}}), or
     * an empty list. The week is not checked against the schedule; out-of-range weeks give an
     * empty list. Use {@link dev.brickfolio.jsleeper.util.Matchups#pair} to group the rows.
     *
     * @throws IllegalArgumentException if {@code leagueId} is blank or {@code week} is below 1
     */
    public List<Matchup> matchups(String leagueId, int week) {
        Args.requireId("leagueId", leagueId);
        Args.requirePositive("week", week);
        return requester.get(List.of("league", leagueId, "matchups", Integer.toString(week)),
                Map.of(), MATCHUP_LIST_TYPE, EmptyKind.LIST, null);
    }

    /**
     * Returns the winners bracket ({@code /league/{leagueId}/winners_bracket}), or an empty list
     * when no bracket exists yet.
     *
     * @throws IllegalArgumentException if {@code leagueId} is null or blank
     */
    public List<BracketMatch> winnersBracket(String leagueId) {
        return bracket(leagueId, "winners_bracket");
    }

    /**
     * Returns the losers bracket, or an empty list when none exists yet. The path is
     * {@code losers_bracket}; the Sleeper docs' {@code loses_bracket} is a typo.
     *
     * @throws IllegalArgumentException if {@code leagueId} is null or blank
     */
    public List<BracketMatch> losersBracket(String leagueId) {
        return bracket(leagueId, "losers_bracket");
    }

    private List<BracketMatch> bracket(String leagueId, String segment) {
        Args.requireId("leagueId", leagueId);
        return requester.get(List.of("league", leagueId, segment), Map.of(), BRACKET_LIST_TYPE,
                EmptyKind.LIST, null);
    }

    /**
     * Returns the trades, free-agent moves and waiver claims of a week
     * ({@code /league/{leagueId}/transactions/{week}}), or an empty list. The Sleeper docs call
     * the week parameter {@code round}.
     *
     * @throws IllegalArgumentException if {@code leagueId} is blank or {@code week} is below 1
     */
    public List<Transaction> transactions(String leagueId, int week) {
        Args.requireId("leagueId", leagueId);
        Args.requirePositive("week", week);
        return requester.get(List.of("league", leagueId, "transactions", Integer.toString(week)),
                Map.of(), TRANSACTION_LIST_TYPE, EmptyKind.LIST, null);
    }

    /**
     * Returns all traded picks in a league, including future picks
     * ({@code /league/{leagueId}/traded_picks}), or an empty list.
     *
     * @throws IllegalArgumentException if {@code leagueId} is null or blank
     */
    public List<TradedPick> tradedPicks(String leagueId) {
        Args.requireId("leagueId", leagueId);
        return requester.get(List.of("league", leagueId, "traded_picks"), Map.of(),
                TRADED_PICK_LIST_TYPE, EmptyKind.LIST, null);
    }

    /**
     * Returns a league's drafts, most recent first ({@code /league/{leagueId}/drafts}), or an
     * empty list. Dynasty leagues can have several. These entries leave {@code slotToRosterId}
     * empty; fetch a single draft with {@code drafts().get} to fill it.
     *
     * @throws IllegalArgumentException if {@code leagueId} is null or blank
     */
    public List<Draft> drafts(String leagueId) {
        Args.requireId("leagueId", leagueId);
        return requester.get(List.of("league", leagueId, "drafts"), Map.of(), DRAFT_LIST_TYPE,
                EmptyKind.LIST, null);
    }
}
