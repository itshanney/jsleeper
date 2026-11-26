package dev.brickfolio.jsleeper.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.brickfolio.jsleeper.SleeperClient;
import dev.brickfolio.jsleeper.api.PlayerQuery;
import dev.brickfolio.jsleeper.api.TrendingQuery;
import dev.brickfolio.jsleeper.model.Draft;
import dev.brickfolio.jsleeper.model.DraftPick;
import dev.brickfolio.jsleeper.model.DraftStatus;
import dev.brickfolio.jsleeper.model.DraftType;
import dev.brickfolio.jsleeper.model.League;
import dev.brickfolio.jsleeper.model.LeagueStatus;
import dev.brickfolio.jsleeper.model.LeagueUser;
import dev.brickfolio.jsleeper.model.Matchup;
import dev.brickfolio.jsleeper.model.Player;
import dev.brickfolio.jsleeper.model.Roster;
import dev.brickfolio.jsleeper.model.SeasonType;
import dev.brickfolio.jsleeper.model.Sport;
import dev.brickfolio.jsleeper.model.SportState;
import dev.brickfolio.jsleeper.model.Transaction;
import dev.brickfolio.jsleeper.model.TransactionStatus;
import dev.brickfolio.jsleeper.model.TransactionType;
import dev.brickfolio.jsleeper.model.TrendType;
import dev.brickfolio.jsleeper.model.TrendingPlayer;
import dev.brickfolio.jsleeper.model.User;
import dev.brickfolio.jsleeper.model.bracket.BracketMatch;
import dev.brickfolio.jsleeper.util.Matchups;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Calls every endpoint once against the real Sleeper API (about 20 calls) to catch schema drift.
 * Assertions are about shape and cross-call consistency, not exact values. Skipped when no league ID
 * is configured. IDs are compared as booleans so a failure never prints the league ID.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveSmokeTest {

    private SleeperClient sleeper;
    private String leagueId;
    private League league;
    private List<Roster> rosters;
    private Set<Integer> rosterIds;

    @BeforeAll
    void resolveLeague() {
        var configured = LiveConfig.leagueId();
        assumeTrue(configured.isPresent(), "no league ID configured (set SLEEPER_LEAGUE_ID or .env)");
        leagueId = configured.get();
        sleeper = SleeperClient.create();
        league = sleeper.leagues().get(leagueId).orElseThrow(
                () -> new AssertionError("the configured league does not exist on Sleeper"));
        rosters = sleeper.leagues().rosters(leagueId);
        rosterIds = rosters.stream().map(Roster::rosterId).collect(Collectors.toSet());
    }

    @Test
    void state_for_nfl_has_the_expected_shape() {
        SportState state = sleeper.state().get(Sport.NFL);

        assertThat(state.season()).matches("\\d{4}");
        assertThat(state.leagueSeason()).matches("\\d{4}");
        assertThat(state.displayWeek()).isNotNull();
        assertThat(state.seasonType()).as("season_type %s", state.seasonTypeRaw()).isNotEqualTo(SeasonType.UNKNOWN);
    }

    @Test
    void league_has_the_expected_shape() {
        assertThat(league.leagueId().equals(leagueId)).as("league id round-trips").isTrue();
        assertThat(league.name()).isNotBlank();
        assertThat(league.sport()).isEqualTo(Sport.NFL);
        assertThat(league.status()).as("status %s", league.statusRaw()).isNotEqualTo(LeagueStatus.UNKNOWN);
        assertThat(league.totalRosters()).isPositive();
        assertThat(league.rosterPositions()).isNotEmpty();
        assertThat(league.scoringSettings()).isNotEmpty();
        assertThat(league.settings()).isNotEmpty();
    }

    @Test
    void rosters_match_the_league_size_and_have_unique_ids() {
        assertThat(rosters).hasSize(league.totalRosters());
        assertThat(rosterIds).hasSameSizeAs(rosters);
        assertThat(rosters).allSatisfy(r -> {
            assertThat(r.leagueId().equals(leagueId)).as("roster belongs to the league").isTrue();
            assertThat(r.settings().wins()).isNotNull();
            assertThat(r.settings().pointsFor().signum()).isGreaterThanOrEqualTo(0);
        });
    }

    @Test
    void league_users_cover_every_roster_owner() {
        List<LeagueUser> users = sleeper.leagues().users(leagueId);
        Set<String> userIds = users.stream().map(LeagueUser::userId).collect(Collectors.toSet());

        assertThat(users).isNotEmpty();
        assertThat(users).allSatisfy(u -> assertThat(u.displayName()).isNotBlank());
        assertThat(rosters).filteredOn(r -> r.ownerId() != null)
                .allSatisfy(r -> assertThat(userIds.contains(r.ownerId())).as("owner is a league user").isTrue());
    }

    @Test
    void week_one_matchups_reference_known_rosters_and_pair_up() {
        List<Matchup> rows = sleeper.leagues().matchups(leagueId, 1);

        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(m -> assertThat(rosterIds).contains(m.rosterId()));
        assertThat(Matchups.pair(rows)).isNotEmpty();
    }

    @Test
    void week_one_transactions_have_known_types_and_rosters() {
        List<Transaction> txs = sleeper.leagues().transactions(leagueId, 1);   // may legitimately be empty

        assertThat(txs).allSatisfy(t -> {
            assertThat(t.type()).as("type %s", t.typeRaw()).isNotEqualTo(TransactionType.UNKNOWN);
            assertThat(t.status()).as("status %s", t.statusRaw()).isNotEqualTo(TransactionStatus.UNKNOWN);
            assertThat(rosterIds).containsAll(t.rosterIds());
        });
    }

    @Test
    void winners_and_losers_brackets_have_rounds_and_matches() {
        List<BracketMatch> winners = sleeper.leagues().winnersBracket(leagueId);
        List<BracketMatch> losers = sleeper.leagues().losersBracket(leagueId);

        assertThat(winners).isNotEmpty();
        assertThat(losers).isNotEmpty();
        assertThat(winners).allSatisfy(m -> {
            assertThat(m.round()).isPositive();
            assertThat(m.matchId()).isPositive();
            assertThat(m.team1()).isNotNull();
            assertThat(m.team2()).isNotNull();
        });
    }

    @Test
    void league_traded_picks_deserialise() {
        assertThat(sleeper.leagues().tradedPicks(leagueId)).allSatisfy(p -> {
            assertThat(p.season()).matches("\\d{4}");
            assertThat(p.round()).isPositive();
        });
    }

    @Test
    void league_drafts_include_the_leagues_draft() {
        List<Draft> drafts = sleeper.leagues().drafts(leagueId);

        assertThat(drafts).isNotEmpty();
        assertThat(drafts).anySatisfy(d -> assertThat(d.draftId().equals(league.draftId())).isTrue());
    }

    @Test
    void single_draft_picks_and_traded_picks_are_consistent() {
        assumeTrue(league.draftId() != null, "league has no draft");
        String draftId = league.draftId();

        Draft draft = sleeper.drafts().get(draftId).orElseThrow(() -> new AssertionError("draft missing"));
        List<DraftPick> picks = sleeper.drafts().picks(draftId);

        assertThat(draft.draftId().equals(draftId)).isTrue();
        assertThat(draft.leagueId().equals(leagueId)).isTrue();
        assertThat(draft.type()).as("type %s", draft.typeRaw()).isNotEqualTo(DraftType.UNKNOWN);
        assertThat(draft.status()).as("status %s", draft.statusRaw()).isNotEqualTo(DraftStatus.UNKNOWN);
        assertThat(draft.settings().rounds()).isPositive();
        assertThat(draft.slotToRosterId()).isNotEmpty();
        assertThat(picks).isNotEmpty();
        assertThat(picks).allSatisfy(p -> assertThat(p.draftId().equals(draftId)).isTrue());
        assertThat(picks).extracting(DraftPick::pickNo).isSorted();
        assertThat(sleeper.drafts().tradedPicks(draftId)).isNotNull();
    }

    @Test
    void an_owner_can_be_looked_up_and_their_leagues_and_drafts_include_this_league() {
        String ownerId = rosters.stream().map(Roster::ownerId).filter(id -> id != null).findFirst().orElseThrow();
        String season = league.season();

        User user = sleeper.users().get(ownerId).orElseThrow(() -> new AssertionError("owner missing"));
        List<League> leagues = sleeper.users().leagues(ownerId, Sport.NFL, season);
        List<Draft> drafts = sleeper.users().drafts(ownerId, Sport.NFL, season);

        assertThat(user.userId().equals(ownerId)).isTrue();
        assertThat(user.displayName()).isNotBlank();
        assertThat(leagues).anySatisfy(l -> assertThat(l.leagueId().equals(leagueId)).isTrue());
        assertThat(drafts).isNotEmpty();
    }

    @Test
    void active_quarterbacks_only_not_the_full_players_map() {
        Map<String, Player> qbs = sleeper.players().all(Sport.NFL,
                PlayerQuery.builder().position("QB").activeOnly(true).build());

        assertThat(qbs).isNotEmpty();
        assertThat(qbs.values()).allSatisfy(p -> {
            assertThat(p.fantasyPositions()).contains("QB");
            assertThat(p.fullName()).isNotBlank();
            assertThat(p.active()).isTrue();
        });
    }

    @Test
    void trending_adds_and_drops_respect_the_limit() {
        TrendingQuery five = TrendingQuery.builder().limit(5).build();

        List<TrendingPlayer> adds = sleeper.players().trending(Sport.NFL, TrendType.ADD, five);
        List<TrendingPlayer> drops = sleeper.players().trending(Sport.NFL, TrendType.DROP, five);

        assertThat(adds).isNotEmpty().hasSizeLessThanOrEqualTo(5);
        assertThat(drops).isNotEmpty().hasSizeLessThanOrEqualTo(5);
        assertThat(adds).allSatisfy(t -> {
            assertThat(t.playerId()).isNotBlank();
            assertThat(t.count()).isPositive();
        });
    }
}
