package dev.brickfolio.jsleeper.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.brickfolio.jsleeper.Fixtures;
import dev.brickfolio.jsleeper.SleeperClient;
import dev.brickfolio.jsleeper.error.SleeperTransportException;
import dev.brickfolio.jsleeper.model.Player;
import dev.brickfolio.jsleeper.model.Sport;
import dev.brickfolio.jsleeper.model.TrendType;
import dev.brickfolio.jsleeper.model.TrendingPlayer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class PlayersApiTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    private final SleeperClient client = SleeperClient.builder().baseUri(wm.baseUrl() + "/v1").build();

    private void stub(String url, byte[] body) {
        wm.stubFor(get(urlEqualTo(url)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private void stub(String url, String body) {
        stub(url, body.getBytes(StandardCharsets.UTF_8));
    }

    // ---- all() ----

    @Test
    void sample_fixture_deserialises_with_team_defense_and_null_team_player() {
        stub("/v1/players/nfl", Fixtures.bytes("players_nfl_sample.json"));

        Map<String, Player> players = client.players().all(Sport.NFL);

        assertThat(players).hasSize(16).containsKeys("KC", "96");
        Player kc = players.get("KC");
        assertThat(kc.playerId()).isEqualTo("KC");
        assertThat(kc.position()).isEqualTo("DEF");
        assertThat(kc.team()).isEqualTo("KC");
        assertThat(kc.firstName()).isEqualTo("Kansas City");
        assertThat(kc.fullName()).isEqualTo("Kansas City Chiefs");
        assertThat(kc.fantasyPositions()).containsExactly("DEF");
        assertThat(kc.number()).isNull();
        assertThat(kc.externalIds().espnId()).isNull();

        Player rodgers = players.get("96");
        assertThat(rodgers.team()).isNull();
        assertThat(rodgers.firstName()).isEqualTo("Aaron");
        assertThat(rodgers.fullName()).isEqualTo("Aaron Rodgers");
        assertThat(rodgers.active()).isTrue();
        assertThat(rodgers.externalIds().statsId()).isEqualTo("213957");
        assertThat(rodgers.externalIds().rotowireId()).isEqualTo("4307");
        assertThat(rodgers.externalIds().rotoworldId()).isEqualTo("3118");
        assertThat(rodgers.externalIds().gsisId()).isEqualTo("00-0023459");
        assertThat(rodgers.searchRank()).isEqualTo(186);
        assertThat(rodgers.injuryStartDate()).isNull();
    }

    @Test
    void every_sampled_player_deserialises_with_a_name_and_id() {
        stub("/v1/players/nfl", Fixtures.bytes("players_nfl_sample.json"));

        Map<String, Player> players = client.players().all(Sport.NFL);

        assertThat(players).allSatisfy((id, p) -> {
            assertThat(p.playerId()).isEqualTo(id);
            assertThat(p.fullName()).isNotBlank();
            assertThat(p.fantasyPositions()).isNotNull();
        });
    }

    @Test
    void mixed_int_and_string_types_are_coerced() {
        stub("/v1/players/nfl",
                "{\"1\":{\"player_id\":\"1\",\"number\":12,\"weight\":220,\"depth_chart_position\":1,"
                        + "\"espn_id\":\"\",\"yahoo_id\":4046,\"fantasy_data_id\":\"17836\","
                        + "\"age\":\"40\",\"full_name\":\"Tom Brady\"}}");

        Player p = client.players().all(Sport.NFL).get("1");

        assertThat(p.number()).isEqualTo(12);
        assertThat(p.weight()).isEqualTo("220");
        assertThat(p.depthChartPosition()).isEqualTo("1");
        assertThat(p.age()).isEqualTo(40);
        assertThat(p.externalIds().espnId()).isNull();
        assertThat(p.externalIds().yahooId()).isEqualTo("4046");
        assertThat(p.externalIds().fantasyDataId()).isEqualTo("17836");
        assertThat(p.fullName()).isEqualTo("Tom Brady");
    }

    @Test
    void full_name_falls_back_to_first_and_last_then_to_player_id() {
        stub("/v1/players/nfl",
                "{\"a\":{\"player_id\":\"a\",\"first_name\":\"Ann\",\"last_name\":\"Lee\"},"
                        + "\"b\":{\"player_id\":\"b\",\"last_name\":\"Solo\"},"
                        + "\"c\":{\"player_id\":\"c\"}}");

        Map<String, Player> players = client.players().all(Sport.NFL);

        assertThat(players.get("a").fullName()).isEqualTo("Ann Lee");
        assertThat(players.get("b").fullName()).isEqualTo("Solo");
        assertThat(players.get("c").fullName()).isEqualTo("c");
    }

    @Test
    void qb_active_sample_deserialises_and_query_string_is_position_then_active() {
        stub("/v1/players/nfl?position=QB&active=true", Fixtures.bytes("players_nfl_qb_active_sample.json"));

        Map<String, Player> qbs = client.players().all(Sport.NFL,
                PlayerQuery.builder().position("QB").activeOnly(true).build());

        assertThat(qbs).hasSize(5);
        assertThat(qbs.values()).allSatisfy(p -> assertThat(p.position()).isEqualTo("QB"));
        wm.verify(getRequestedFor(urlEqualTo("/v1/players/nfl?position=QB&active=true")));
    }

    @Test
    void active_param_is_not_sent_unless_requested() {
        stub("/v1/players/nfl?position=RB", "{}");

        client.players().all(Sport.NFL, PlayerQuery.builder().position("RB").activeOnly(false).build());

        wm.verify(getRequestedFor(urlEqualTo("/v1/players/nfl?position=RB")));
    }

    @Test
    void active_only_without_position_sends_just_active() {
        stub("/v1/players/nfl?active=true", "{}");

        client.players().all(Sport.NFL, PlayerQuery.builder().activeOnly(true).build());

        wm.verify(getRequestedFor(urlEqualTo("/v1/players/nfl?active=true")));
    }

    @Test
    void empty_players_response_gives_empty_map() {
        stub("/v1/players/nfl", "null");

        assertThat(client.players().all(Sport.NFL)).isEmpty();
    }

    @Test
    void blank_position_is_rejected_and_unknown_sport_rejected_before_any_request() {
        assertThatThrownBy(() -> PlayerQuery.builder().position(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.players().all(Sport.UNKNOWN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.players().trending(null, TrendType.ADD))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    // ---- timeouts ----

    @Test
    void unfiltered_call_uses_the_long_timeout_but_filtered_call_uses_the_default() {
        wm.stubFor(get(urlEqualTo("/v1/players/nfl")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}").withFixedDelay(600)));
        wm.stubFor(get(urlEqualTo("/v1/players/nfl?position=QB")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}").withFixedDelay(600)));
        SleeperClient shortTimeout = SleeperClient.builder().baseUri(wm.baseUrl() + "/v1")
                .requestTimeout(Duration.ofMillis(200))
                .retryPolicy(dev.brickfolio.jsleeper.RetryPolicy.none()).build();

        assertThat(shortTimeout.players().all(Sport.NFL)).isEmpty();
        assertThatThrownBy(() -> shortTimeout.players().all(Sport.NFL, PlayerQuery.builder().position("QB").build()))
                .isInstanceOf(SleeperTransportException.class);
    }

    // ---- trending ----

    @Test
    void trending_fixtures_deserialise_including_a_team_code_id() {
        stub("/v1/players/nfl/trending/add", Fixtures.bytes("trending_add.json"));
        stub("/v1/players/nfl/trending/drop", Fixtures.bytes("trending_drop.json"));

        List<TrendingPlayer> adds = client.players().trending(Sport.NFL, TrendType.ADD);
        List<TrendingPlayer> drops = client.players().trending(Sport.NFL, TrendType.DROP);

        assertThat(adds).hasSize(10);
        assertThat(adds.get(0)).isEqualTo(new TrendingPlayer("WAS", 487410));
        assertThat(adds.get(1)).isEqualTo(new TrendingPlayer("4017", 320032));
        assertThat(drops).isNotEmpty().allSatisfy(t -> assertThat(t.count()).isPositive());
    }

    @Test
    void trending_query_sends_lookback_hours_and_limit_in_order() {
        stub("/v1/players/nfl/trending/add?lookback_hours=48&limit=10", "[]");

        List<TrendingPlayer> result = client.players().trending(Sport.NFL, TrendType.ADD,
                TrendingQuery.builder().lookbackHours(48).limit(10).build());

        assertThat(result).isEmpty();
        wm.verify(getRequestedFor(urlEqualTo("/v1/players/nfl/trending/add?lookback_hours=48&limit=10")));
    }

    @Test
    void trending_query_sends_only_the_options_that_were_set() {
        stub("/v1/players/nfl/trending/drop?limit=5", "[]");

        client.players().trending(Sport.NFL, TrendType.DROP, TrendingQuery.builder().limit(5).build());

        wm.verify(getRequestedFor(urlEqualTo("/v1/players/nfl/trending/drop?limit=5")));
    }

    @Test
    void trending_query_validates_minimums_in_build() {
        assertThatThrownBy(() -> TrendingQuery.builder().lookbackHours(0).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrendingQuery.builder().limit(0).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrendingQuery.builder().limit(-3).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(TrendingQuery.builder().lookbackHours(1).limit(1).build()).isNotNull();
    }

    @Test
    void trend_type_wire_values() {
        assertThat(TrendType.ADD.wireValue()).isEqualTo("add");
        assertThat(TrendType.DROP.wireValue()).isEqualTo("drop");
    }

    @Test
    void usage_example_trending_adds_compiles_with_a_plain_map_instead_of_player_directory() {
        stub("/v1/players/nfl", Fixtures.bytes("players_nfl_sample.json"));
        stub("/v1/players/nfl/trending/add?lookback_hours=48&limit=10",
                "[{\"player_id\":\"96\",\"count\":1234567},{\"player_id\":\"WAS\",\"count\":42}]");
        SleeperClient sleeper = client;
        Map<String, Player> players = sleeper.players().all(Sport.NFL);
        List<String> out = new ArrayList<>();

        // Usage Examples section 8; `players.get(id)` is a Map lookup until PlayerDirectory exists
        // (increment 19), and the example's final test lands in increment 21.
        List<TrendingPlayer> hot = sleeper.players().trending(Sport.NFL, TrendType.ADD,
                TrendingQuery.builder().lookbackHours(48).limit(10).build());

        hot.forEach(t -> out.add(String.format("%-22s +%,d%n",
                Optional.ofNullable(players.get(t.playerId())).map(Player::fullName).orElse(t.playerId()),
                t.count())));

        assertThat(out.get(0)).startsWith("Aaron Rodgers").contains("+1,234,567");
        assertThat(out.get(1)).startsWith("WAS ").contains("+42");
    }
}
