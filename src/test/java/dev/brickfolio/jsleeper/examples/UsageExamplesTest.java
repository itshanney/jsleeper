package dev.brickfolio.jsleeper.examples;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.brickfolio.jsleeper.Fixtures;
import dev.brickfolio.jsleeper.RateLimit;
import dev.brickfolio.jsleeper.RequestEvent;
import dev.brickfolio.jsleeper.RequestListener;
import dev.brickfolio.jsleeper.RetryPolicy;
import dev.brickfolio.jsleeper.SleeperClient;
import dev.brickfolio.jsleeper.api.TrendingQuery;
import dev.brickfolio.jsleeper.error.SleeperApiException;
import dev.brickfolio.jsleeper.error.SleeperDeserializationException;
import dev.brickfolio.jsleeper.error.SleeperRateLimitException;
import dev.brickfolio.jsleeper.error.SleeperServerException;
import dev.brickfolio.jsleeper.error.SleeperTransportException;
import dev.brickfolio.jsleeper.model.Draft;
import dev.brickfolio.jsleeper.model.DraftPick;
import dev.brickfolio.jsleeper.model.DraftPickMetadata;
import dev.brickfolio.jsleeper.model.League;
import dev.brickfolio.jsleeper.model.LeagueUser;
import dev.brickfolio.jsleeper.model.Matchup;
import dev.brickfolio.jsleeper.model.MatchupPair;
import dev.brickfolio.jsleeper.model.Player;
import dev.brickfolio.jsleeper.model.Roster;
import dev.brickfolio.jsleeper.model.RosterSettings;
import dev.brickfolio.jsleeper.model.Sport;
import dev.brickfolio.jsleeper.model.SportState;
import dev.brickfolio.jsleeper.model.TrendType;
import dev.brickfolio.jsleeper.model.TrendingPlayer;
import dev.brickfolio.jsleeper.model.Transaction;
import dev.brickfolio.jsleeper.model.User;
import dev.brickfolio.jsleeper.model.bracket.BracketMatch;
import dev.brickfolio.jsleeper.model.bracket.BracketSlot;
import dev.brickfolio.jsleeper.players.PlayerDirectory;
import dev.brickfolio.jsleeper.util.Avatars;
import dev.brickfolio.jsleeper.util.Matchups;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Usage Examples of {@code specs/2026-11-26-sleeper-java-client.md}, one test per section, each
 * run against WireMock serving the recorded and hand-edited fixtures. Snippets are copied from the
 * spec; the only changes are listed here. This file is canonical: when an API change breaks an
 * example, update the spec's Usage Examples to match in the same change.
 *
 * <ul>
 *   <li>{@code System.out.printf} becomes {@code out.add(String.format(...))} with the same format
 *       strings.</li>
 *   <li>IDs and the player ID are replaced with ones present in the fixtures.</li>
 *   <li>{@code MicrometerSleeperListener} is a local {@link RequestListener}; {@code render},
 *       {@code ok}, {@code notFound}, {@code retryLater}, {@code serviceUnavailable} and {@code log}
 *       are local stand-ins for the caller's own code.</li>
 *   <li>The customised client in section 1 adds {@code .baseUri(...)} to reach WireMock.</li>
 * </ul>
 */
class UsageExamplesTest {

    private static final String LEAGUE_ID = "1391586806968889344";
    private static final String USER_ID = "1266175725602353152";
    private static final String DRAFT_ID = "1391586807681945600";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    private final List<String> out = new ArrayList<>();

    /** A client for WireMock that never sleeps for backoff or rate limiting. */
    private SleeperClient testClient() {
        return SleeperClient.builder().baseUri(wm.baseUrl() + "/v1")
                .retryPolicy(RetryPolicy.none()).rateLimit(RateLimit.disabled()).build();
    }

    private void stub(String path, int status, String contentType, byte[] body) {
        wm.stubFor(get(urlEqualTo("/v1" + path)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", contentType).withBody(body)));
    }

    private void stubFixture(String path, String fixture) {
        stub(path, 200, "application/json", Fixtures.bytes(fixture));
    }

    private void stubJson(String path, int status, String body) {
        stub(path, status, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    private int requestsTo(String path) {
        return wm.findAll(getRequestedFor(urlEqualTo("/v1" + path))).size();
    }

    // =====================================================================================
    // 1. Creating a client
    // =====================================================================================

    /** Stands in for the caller's metrics listener ({@code new MicrometerSleeperListener(registry)}). */
    static final class MicrometerSleeperListener implements RequestListener {
        private final List<String> registry;

        MicrometerSleeperListener(List<String> registry) {
            this.registry = registry;
        }

        @Override
        public void onRequest(RequestEvent event) {
            registry.add(event.method() + " " + event.pathTemplate() + " attempt " + event.attempt());
        }
    }

    @Test
    void section_1_creating_a_client() {
        {
            // Defaults: production base URL, 900 req/min limiter, 3-attempt retry, 10s timeout.
            SleeperClient sleeper = SleeperClient.create();

            assertThat(sleeper.state()).isNotNull();   // built without any network call
            assertThat(sleeper.users()).isNotNull();
            assertThat(sleeper.leagues()).isNotNull();
            assertThat(sleeper.drafts()).isNotNull();
            assertThat(sleeper.players()).isNotNull();
        }
        stubFixture("/state/nfl", "state_nfl.json");
        List<String> registry = new ArrayList<>();
        {
            // Customised. Build once and share; the client is thread-safe.
            SleeperClient sleeper = SleeperClient.builder()
                    .baseUri(wm.baseUrl() + "/v1")
                    .userAgent("brickfolio-dashboard/1.0")
                    .requestTimeout(Duration.ofSeconds(5))
                    .rateLimit(RateLimit.perMinute(600).burst(20).maxWait(Duration.ofSeconds(10)))
                    .retryPolicy(RetryPolicy.builder()
                            .maxAttempts(4)
                            .initialBackoff(Duration.ofMillis(250))
                            .maxBackoff(Duration.ofSeconds(5))
                            .build())
                    .listener(new MicrometerSleeperListener(registry))   // caller's own RequestListener
                    .build();

            SportState state = sleeper.state().get(Sport.NFL);

            assertThat(state.displayWeek()).isEqualTo(12);
        }
        wm.verify(getRequestedFor(urlEqualTo("/v1/state/nfl")).withHeader("User-Agent", equalTo("brickfolio-dashboard/1.0")));
        assertThat(registry).containsExactly("GET /state/{sport} attempt 1");
    }

    // =====================================================================================
    // 2. Username -> this season's leagues
    // =====================================================================================

    @Test
    void section_2_username_to_this_seasons_leagues() {
        stubFixture("/user/someuser", "user.json");
        stubFixture("/state/nfl", "state_nfl.json");
        stubFixture("/user/" + USER_ID + "/leagues/nfl/2026", "user_leagues.json");
        SleeperClient sleeper = testClient();

        User user = sleeper.users().get("someuser")
                .orElseThrow(() -> new IllegalArgumentException("No such Sleeper user"));

        SportState nfl = sleeper.state().get(Sport.NFL);

        List<League> leagues = sleeper.users().leagues(user.userId(), Sport.NFL, nfl.leagueSeason());

        for (League league : leagues) {
            out.add(String.format("%s  [%s]  %d teams  status=%s%n",
                    league.name(), league.leagueId(), league.totalRosters(), league.status()));
        }

        assertThat(out).containsExactly(
                String.format("Test League  [%s]  12 teams  status=IN_SEASON%n", LEAGUE_ID));
        assertThat(requestsTo("/user/" + USER_ID + "/leagues/nfl/2026")).isEqualTo(1);
    }

    // =====================================================================================
    // 3. Standings
    // =====================================================================================

    @Test
    void section_3_standings() {
        stubFixture("/league/" + LEAGUE_ID + "/users", "league_users.json");
        stubFixture("/league/" + LEAGUE_ID + "/rosters", "league_rosters.json");
        SleeperClient sleeper = testClient();

        String leagueId = "1391586806968889344";

        Map<String, LeagueUser> usersById = sleeper.leagues().users(leagueId).stream()
                .collect(Collectors.toMap(LeagueUser::userId, Function.identity()));

        List<Roster> standings = sleeper.leagues().rosters(leagueId).stream()
                .sorted(Comparator
                        .comparing((Roster r) -> r.settings().wins()).reversed()
                        .thenComparing(r -> r.settings().pointsFor(), Comparator.reverseOrder()))
                .toList();

        for (Roster r : standings) {
            LeagueUser owner = usersById.get(r.ownerId());          // null if the team is orphaned
            String team = owner == null ? "(orphan)"
                    : owner.teamName().orElse(owner.displayName());
            RosterSettings s = r.settings();
            out.add(String.format("%-24s %d-%d-%d  PF %s  PA %s%n",
                    team, s.wins(), s.losses(), s.ties(), s.pointsFor(), s.pointsAgainst()));
        }

        assertThat(out).hasSize(12);
        Pattern record = Pattern.compile(" (\\d+)-(\\d+)-(\\d+)  PF ");
        List<Integer> wins = out.stream().map(l -> {
            Matcher m = record.matcher(l);
            assertThat(m.find()).as(l).isTrue();
            return Integer.parseInt(m.group(1));
        }).toList();
        assertThat(wins).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(out.get(0)).contains(" 8-3-0  PF 1286.40  PA 1119.32");
        assertThat(out).anyMatch(l -> l.startsWith("Team "));             // owners who set a team name
        assertThat(out).anyMatch(l -> l.startsWith("User "));             // others fall back to display name
        assertThat(out).noneMatch(l -> l.startsWith("(orphan)"));
    }

    // =====================================================================================
    // 4. Weekly scoreboard with player names
    // =====================================================================================

    /** The loop of section 4, shared so the fixture week and the bye-week edge fixture both run it. */
    private void scoreboard(SleeperClient sleeper, PlayerDirectory players, String leagueId, int week) {
        List<MatchupPair> pairs = Matchups.pair(sleeper.leagues().matchups(leagueId, week));

        for (MatchupPair pair : pairs) {
            Matchup home = pair.home();
            Matchup away = pair.away();                     // null on a bye / median-only row
            out.add(String.format("Roster %d  %.2f  vs  %s%n",
                    home.rosterId(), home.effectivePoints(),
                    away == null ? "BYE" : "%.2f  Roster %d".formatted(away.effectivePoints(), away.rosterId())));

            for (String playerId : home.starters()) {
                String name = players.get(playerId)          // first call loads ~5 MB once, then memory
                        .map(Player::fullName)
                        .orElse(playerId);                   // e.g. "DET" for a team defense
                double pts = home.playersPoints().getOrDefault(playerId, 0.0);
                out.add(String.format("    %-22s %6.2f%n", name, pts));
            }
        }
    }

    @Test
    void section_4_weekly_scoreboard_with_player_names() {
        stubFixture("/state/nfl", "state_nfl.json");
        stubFixture("/league/" + LEAGUE_ID + "/matchups/12", "league_matchups_w1.json");
        stubFixture("/league/" + LEAGUE_ID + "/matchups/13", "edge_matchups_bye_custom.json");
        stubFixture("/players/nfl", "players_nfl_sample.json");
        SleeperClient sleeper = testClient();
        String leagueId = LEAGUE_ID;

        PlayerDirectory players = PlayerDirectory.builder(sleeper)
                .sport(Sport.NFL)
                .ttl(Duration.ofHours(24))
                .build();                                   // lazy: no HTTP call yet
        assertThat(requestsTo("/players/nfl")).isZero();

        int week = sleeper.state().get(Sport.NFL).displayWeek();
        scoreboard(sleeper, players, leagueId, week);

        assertThat(week).isEqualTo(12);
        long headers = out.stream().filter(l -> l.startsWith("Roster ")).count();
        long starters = out.stream().filter(l -> l.startsWith("    ")).count();
        assertThat(headers).isEqualTo(6);
        assertThat(starters).isEqualTo(54);
        assertThat(out.get(0)).startsWith("Roster 1  ").contains("  vs  ").contains("  Roster 9");
        assertThat(out).anyMatch(l -> l.contains("Chase Brown"));       // resolved through the directory
        assertThat(out).anyMatch(l -> l.contains("Aaron Rodgers"));
        assertThat(out).anyMatch(l -> l.matches("    9\\d{3}\\s+\\d+\\.\\d\\d\\s*"));  // unresolved ID falls back
        assertThat(requestsTo("/players/nfl")).as("loaded once, then served from memory").isEqualTo(1);

        out.clear();
        scoreboard(sleeper, players, leagueId, 13);   // edge fixture: a custom-points row and a bye row

        assertThat(out.get(0)).startsWith("Roster 4  " + String.format("%.2f", 150.5) + "  vs  ");
        assertThat(out).anyMatch(l -> l.startsWith("Roster 7  ") && l.endsWith("vs  BYE" + String.format("%n")));
        assertThat(requestsTo("/players/nfl")).isEqualTo(1);
    }

    // =====================================================================================
    // 5. Transactions for a week
    // =====================================================================================

    private void transactionsForAWeek(SleeperClient sleeper, String leagueId, int week) {
        for (Transaction tx : sleeper.leagues().transactions(leagueId, week)) {
            String summary = switch (tx.type()) {
                case TRADE      -> "Trade between rosters " + tx.rosterIds();
                case WAIVER     -> "Waiver claim" + tx.waiverBid().stream()
                                        .mapToObj(bid -> " ($" + bid + " FAAB)").findFirst().orElse("");
                case FREE_AGENT -> "Free-agent move";
                case UNKNOWN    -> "Unrecognised type: " + tx.typeRaw();
            };
            out.add(String.format("[%s] %s (%s)%n", tx.created(), summary, tx.status()));

            tx.adds().forEach((playerId, rosterId) ->
                    out.add(String.format("   + %s → roster %d%n", playerId, rosterId)));
            tx.drops().forEach((playerId, rosterId) ->
                    out.add(String.format("   - %s ← roster %d%n", playerId, rosterId)));
            tx.draftPicks().forEach(p ->
                    out.add(String.format("   pick %s R%d: roster %d → %d%n",
                            p.season(), p.round(), p.previousOwnerId(), p.ownerId())));
        }
    }

    @Test
    void section_5_transactions_for_a_week() {
        stubFixture("/league/" + LEAGUE_ID + "/transactions/1", "league_transactions_w1.json");
        stubFixture("/league/" + LEAGUE_ID + "/transactions/2", "edge_transaction_trade_faab.json");
        stubJson("/league/" + LEAGUE_ID + "/transactions/3",
                200, "[{\"transaction_id\":\"9\",\"type\":\"commissioner\",\"status\":\"complete\","
                        + "\"created\":1789599000000}]");
        SleeperClient sleeper = testClient();
        String leagueId = LEAGUE_ID;
        int week = 1;

        transactionsForAWeek(sleeper, leagueId, week);

        assertThat(out).anyMatch(l -> l.contains("Waiver claim ($36 FAAB) (FAILED)"));
        assertThat(out).anyMatch(l -> l.contains("Waiver claim ($0 FAAB) (COMPLETE)"));
        assertThat(out).anyMatch(l -> l.equals(String.format("   + 12545 → roster 9%n")));
        assertThat(out).anyMatch(l -> l.equals(String.format("   - DET ← roster 9%n")));

        out.clear();
        transactionsForAWeek(sleeper, leagueId, 2);        // trade with draft picks
        assertThat(out.get(0)).contains("Trade between rosters [4, 9]").contains("(COMPLETE)");
        assertThat(out).contains(
                String.format("   pick 2027 R1: roster 4 → 9%n"),
                String.format("   pick 2027 R3: roster 9 → 4%n"));

        out.clear();
        transactionsForAWeek(sleeper, leagueId, 3);        // a type this library does not know
        assertThat(out.get(0)).contains("Unrecognised type: commissioner");
    }

    // =====================================================================================
    // 6. Playoff bracket (sealed BracketSlot, exhaustive switch)
    // =====================================================================================

    static String describe(BracketSlot slot) {
        return switch (slot) {
            case BracketSlot.Team(int rosterId)   -> "Roster " + rosterId;
            case BracketSlot.WinnerOf(int match)  -> "Winner of M" + match;
            case BracketSlot.LoserOf(int match)   -> "Loser of M" + match;
            case BracketSlot.Tbd()                -> "TBD";
        };
    }

    @Test
    void section_6_playoff_bracket() {
        stubFixture("/league/" + LEAGUE_ID + "/winners_bracket", "league_winners_bracket.json");
        stubJson("/league/played/winners_bracket", 200,
                "[{\"r\":1,\"m\":1,\"t1\":3,\"t2\":6,\"w\":3,\"l\":6}]");
        SleeperClient sleeper = testClient();
        String leagueId = LEAGUE_ID;

        for (BracketMatch m : sleeper.leagues().winnersBracket(leagueId)) {
            out.add(String.format("R%d M%d: %s vs %s%s%n",
                    m.round(), m.matchId(), describe(m.team1()), describe(m.team2()),
                    m.winner() == null ? "" : "  → winner roster " + m.winner()));
        }

        assertThat(out).hasSize(7);
        assertThat(out.get(0)).isEqualTo(String.format("R1 M1: Roster 11 vs Roster 12%n"));
        assertThat(out.get(2)).isEqualTo(String.format("R2 M3: Roster 1 vs Winner of M1%n"));
        assertThat(out.get(4)).isEqualTo(String.format("R2 M5: Loser of M1 vs Loser of M2%n"));
        assertThat(out.get(5)).isEqualTo(String.format("R3 M6: Winner of M3 vs Winner of M4%n"));
        assertThat(describe(new BracketSlot.Tbd())).isEqualTo("TBD");

        out.clear();
        for (BracketMatch m : sleeper.leagues().winnersBracket("played")) {   // a decided match
            out.add(String.format("R%d M%d: %s vs %s%s%n",
                    m.round(), m.matchId(), describe(m.team1()), describe(m.team2()),
                    m.winner() == null ? "" : "  → winner roster " + m.winner()));
        }
        assertThat(out).containsExactly(String.format("R1 M1: Roster 3 vs Roster 6  → winner roster 3%n"));
    }

    // =====================================================================================
    // 7. Draft board
    // =====================================================================================

    @Test
    void section_7_draft_board() {
        stubFixture("/league/" + LEAGUE_ID, "league.json");
        stubFixture("/draft/" + DRAFT_ID, "draft.json");
        stubFixture("/draft/" + DRAFT_ID + "/picks", "draft_picks.json");
        SleeperClient sleeper = testClient();
        String leagueId = LEAGUE_ID;

        League league = sleeper.leagues().get(leagueId).orElseThrow();
        Draft draft = sleeper.drafts().get(league.draftId()).orElseThrow();  // single GET fills slotToRosterId

        out.add(String.format("%s draft, %d rounds, %s%n",
                draft.type(), draft.settings().rounds(), draft.metadata().scoringType()));

        for (DraftPick pick : sleeper.drafts().picks(draft.draftId())) {
            DraftPickMetadata md = pick.metadata();
            out.add(String.format("%3d. (R%d.%02d) %s %s, %s %s%s%n",
                    pick.pickNo(), pick.round(), pick.draftSlot(),
                    md.firstName(), md.lastName(), md.position(), md.team(),
                    Boolean.TRUE.equals(pick.isKeeper()) ? "  [K]" : ""));
        }

        assertThat(draft.slotToRosterId()).hasSize(12);
        assertThat(out).hasSize(169);
        assertThat(out.get(0)).isEqualTo(String.format("SNAKE draft, 14 rounds, ppr%n"));
        assertThat(out.get(1)).isEqualTo(String.format("  1. (R1.01) Jahmyr Gibbs, RB DET%n"));
        assertThat(out.get(168)).startsWith("168. (R14.");
    }

    // =====================================================================================
    // 8. Trending adds
    // =====================================================================================

    private void trendingLines(SleeperClient sleeper, PlayerDirectory players, TrendType type) {
        List<TrendingPlayer> hot = sleeper.players().trending(Sport.NFL, type,
                TrendingQuery.builder().lookbackHours(48).limit(10).build());

        hot.forEach(t -> out.add(String.format("%-22s +%,d%n",
                players.get(t.playerId()).map(Player::fullName).orElse(t.playerId()), t.count())));
        // Attribution to Sleeper is required wherever this data is displayed (see README).
    }

    @Test
    void section_8_trending_adds() {
        stubFixture("/players/nfl", "players_nfl_sample.json");
        stubFixture("/players/nfl/trending/add?lookback_hours=48&limit=10", "trending_add.json");
        stubFixture("/players/nfl/trending/drop?lookback_hours=48&limit=10", "trending_drop.json");
        SleeperClient sleeper = testClient();
        PlayerDirectory players = PlayerDirectory.builder(sleeper).sport(Sport.NFL).build();

        trendingLines(sleeper, players, TrendType.ADD);

        assertThat(out).hasSize(10);
        assertThat(out.get(0)).isEqualTo(String.format("%-22s +%,d%n", "WAS", 487410));   // a team code, not a player
        assertThat(out.get(1)).startsWith("4017 ");
        assertThat(requestsTo("/players/nfl/trending/add?lookback_hours=48&limit=10")).isEqualTo(1);

        out.clear();
        trendingLines(sleeper, players, TrendType.DROP);   // the most dropped player is in the sample
        assertThat(out.get(0)).isEqualTo(String.format("%-22s +%,d%n", "Aaron Rodgers", 293704));
    }

    // =====================================================================================
    // 9. Fetching a league concurrently (virtual threads)
    // =====================================================================================

    private final List<String> rendered = new ArrayList<>();

    private void render(League league, List<Roster> rosters, List<LeagueUser> users, List<Matchup> matchups) {
        rendered.add(league.name() + "|" + rosters.size() + "|" + users.size() + "|" + matchups.size());
    }

    @Test
    void section_9_fetching_a_league_concurrently() throws Exception {
        stubFixture("/league/" + LEAGUE_ID, "league.json");
        stubFixture("/league/" + LEAGUE_ID + "/rosters", "league_rosters.json");
        stubFixture("/league/" + LEAGUE_ID + "/users", "league_users.json");
        stubFixture("/league/" + LEAGUE_ID + "/matchups/1", "league_matchups_w1.json");
        SleeperClient sleeper = SleeperClient.builder().baseUri(wm.baseUrl() + "/v1").build();  // default limiter
        String leagueId = LEAGUE_ID;
        int week = 1;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<League>           league   = executor.submit(() -> sleeper.leagues().get(leagueId).orElseThrow());
            Future<List<Roster>>     rosters  = executor.submit(() -> sleeper.leagues().rosters(leagueId));
            Future<List<LeagueUser>> users    = executor.submit(() -> sleeper.leagues().users(leagueId));
            Future<List<Matchup>>    matchups = executor.submit(() -> sleeper.leagues().matchups(leagueId, week));

            render(league.get(), rosters.get(), users.get(), matchups.get());
        }

        assertThat(rendered).containsExactly("Test League|12|12|12");
        assertThat(requestsTo("/league/" + LEAGUE_ID)).isEqualTo(1);
        assertThat(requestsTo("/league/" + LEAGUE_ID + "/matchups/1")).isEqualTo(1);
    }

    // =====================================================================================
    // 10. Error handling
    // =====================================================================================

    /** Minimal stand-in for the caller's logger, with SLF4J-style {@code {}} placeholders. */
    private final class Log {
        void error(String format, Object... args) {
            String line = format;
            for (Object arg : args) {
                line = line.replaceFirst("\\{}", Matcher.quoteReplacement(String.valueOf(arg)));
            }
            out.add(line);
        }

        void error(String message, Throwable cause) {
            out.add(message + " <- " + cause.getClass().getSimpleName());
        }
    }

    private final Log log = new Log();

    private String ok(League league) {
        return "ok " + league.name();
    }

    private String notFound() {
        return "not found";
    }

    private String retryLater(Duration after) {
        return "retry later " + after;
    }

    private String serviceUnavailable() {
        return "service unavailable";
    }

    private String handle(SleeperClient sleeper, String leagueId) {
        try {
            Optional<League> maybe = sleeper.leagues().get(leagueId);
            if (maybe.isEmpty()) {
                // Absent ID: 404+null or 200+null from Sleeper. Normal outcome, no exception.
                return notFound();
            }
            return ok(maybe.get());
        } catch (SleeperRateLimitException e) {
            // 429 after retries, or the local limiter couldn't get a token within maxWait.
            return retryLater(Duration.ofMinutes(1));
        } catch (SleeperServerException e) {
            // 5xx after retries; e.status() is 500/503/…
            return serviceUnavailable();
        } catch (SleeperApiException e) {
            // Any other non-2xx, including a 404 with an error body or HTML (unknown route, which is a bug or an API change).
            log.error("Sleeper {} {} -> {}: {}", e.method(), e.path(), e.status(), e.bodySnippet());
            throw e;
        } catch (SleeperTransportException | SleeperDeserializationException e) {
            log.error("Sleeper call failed", e);
            throw e;
        }
    }

    @Test
    void section_10_error_handling_drives_every_branch() {
        SleeperClient sleeper = testClient();
        stubFixture("/league/found", "league.json");
        stubJson("/league/absent", 404, "null");
        stubJson("/league/nullbody", 200, "null");
        stubJson("/league/limited", 429, "slow down");
        stubJson("/league/down", 503, "maintenance");
        stub("/league/typo", 404, "text/html", "<!doctype html><html>Sleeper</html>".getBytes(StandardCharsets.UTF_8));
        stubJson("/league/garbled", 200, "{\"league_id\":");

        assertThat(handle(sleeper, "found")).isEqualTo("ok Test League");
        assertThat(handle(sleeper, "absent")).isEqualTo("not found");                 // JSON 404
        assertThat(handle(sleeper, "nullbody")).isEqualTo("not found");               // 200 + null
        assertThat(handle(sleeper, "limited")).isEqualTo("retry later PT1M");         // 429
        assertThat(handle(sleeper, "down")).isEqualTo("service unavailable");         // 503

        assertThatThrownBy(() -> handle(sleeper, "typo")).isExactlyInstanceOf(SleeperApiException.class);
        assertThat(out).containsExactly("Sleeper GET /league/typo -> 404: <!doctype html><html>Sleeper</html>");

        out.clear();
        assertThatThrownBy(() -> handle(sleeper, "garbled")).isExactlyInstanceOf(SleeperDeserializationException.class);
        assertThat(out).containsExactly("Sleeper call failed <- SleeperDeserializationException");

        out.clear();
        wm.stubFor(get(urlEqualTo("/v1/league/reset")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        SleeperClient unreachable = testClient();
        assertThatThrownBy(() -> handle(unreachable, "reset")).isExactlyInstanceOf(SleeperTransportException.class);
        assertThat(out).containsExactly("Sleeper call failed <- SleeperTransportException");
    }

    @Test
    void section_10_a_client_side_rate_limit_failure_takes_the_rate_limit_branch() {
        stubFixture("/league/found", "league.json");
        SleeperClient sleeper = SleeperClient.builder().baseUri(wm.baseUrl() + "/v1")
                .retryPolicy(RetryPolicy.none())
                .rateLimit(RateLimit.perMinute(1).burst(1).maxWait(Duration.ZERO)).build();
        assertThat(handle(sleeper, "found")).isEqualTo("ok Test League");

        String second = handle(sleeper, "found");

        assertThat(second).isEqualTo("retry later PT1M");
        assertThat(requestsTo("/league/found")).as("nothing was sent the second time").isEqualTo(1);
    }

    // =====================================================================================
    // 11. Persisting the player snapshot across restarts
    // =====================================================================================

    @Test
    void section_11_persisting_the_player_snapshot(@TempDir Path tempDir) throws IOException {
        stubFixture("/players/nfl", "players_nfl_sample.json");
        SleeperClient sleeper = testClient();
        Path snapshot = tempDir.resolve("players-nfl.json");

        // First run: no snapshot yet.
        PlayerDirectory firstRun = PlayerDirectory.builder(sleeper).build();
        out.add(persist(firstRun, snapshot));
        assertThat(requestsTo("/players/nfl")).isEqualTo(1);

        // Restart: the snapshot is under 24h old, so no HTTP call is made.
        PlayerDirectory secondRun = PlayerDirectory.builder(sleeper).build();
        out.add(persist(secondRun, snapshot));

        assertThat(requestsTo("/players/nfl")).isEqualTo(1);
        assertThat(out).hasSize(2).allSatisfy(l -> assertThat(l).isEqualTo("Aaron Rodgers null 8439"));
        assertThat(out.get(0)).isEqualTo(out.get(1));
    }

    /** Section 11; "96" stands in for 4046, which the recorded sample does not contain. */
    private String persist(PlayerDirectory players, Path snapshot) throws IOException {
        if (Files.exists(snapshot)) {
            try (InputStream in = Files.newInputStream(snapshot)) {
                players.loadSnapshot(in);        // keeps the stored loadedAt; no HTTP if < 24h old
            }
        }

        Player mahomes = players.get("96").orElseThrow();   // refreshes only if the TTL has expired
        String line = mahomes.fullName() + " " + mahomes.team() + " " + mahomes.externalIds().espnId();

        try (OutputStream out = Files.newOutputStream(snapshot)) {
            players.writeSnapshot(out);
        }
        return line;
    }

    // =====================================================================================
    // 12. Avatars
    // =====================================================================================

    @Test
    void section_12_avatars() {
        stubFixture("/user/someuser", "user.json");
        SleeperClient sleeper = testClient();
        User user = sleeper.users().get("someuser").orElseThrow();

        URI full  = Avatars.full(user.avatar());    // https://sleepercdn.com/avatars/<id>
        URI thumb = Avatars.thumb(user.avatar());   // https://sleepercdn.com/avatars/thumbs/<id>

        String id = "0".repeat(32);   // the anonymised avatar ID in the fixture
        assertThat(full).isEqualTo(URI.create("https://sleepercdn.com/avatars/" + id));
        assertThat(thumb).isEqualTo(URI.create("https://sleepercdn.com/avatars/thumbs/" + id));
    }
}
