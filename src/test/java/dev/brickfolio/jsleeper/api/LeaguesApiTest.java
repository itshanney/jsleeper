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
import dev.brickfolio.jsleeper.model.League;
import dev.brickfolio.jsleeper.model.LeagueStatus;
import dev.brickfolio.jsleeper.model.LeagueUser;
import dev.brickfolio.jsleeper.model.SeasonType;
import dev.brickfolio.jsleeper.model.Sport;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class LeaguesApiTest {

    private static final String LEAGUE_ID = "1391586806968889344";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    private final SleeperClient client = SleeperClient.builder().baseUri(wm.baseUrl() + "/v1").build();

    private void stub(String url, int status, byte[] body) {
        wm.stubFor(get(urlEqualTo(url)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private void stub(String url, int status, String body) {
        stub(url, status, body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ---- leagues().get ----

    @Test
    void recorded_league_fixture_deserialises() {
        stub("/v1/league/" + LEAGUE_ID, 200, Fixtures.bytes("league.json"));

        League league = client.leagues().get(LEAGUE_ID).orElseThrow();

        assertThat(league.leagueId()).isEqualTo(LEAGUE_ID);
        assertThat(league.name()).isEqualTo("Test League");
        assertThat(league.season()).isEqualTo("2026");
        assertThat(league.seasonType()).isEqualTo(SeasonType.REGULAR);
        assertThat(league.status()).isEqualTo(LeagueStatus.IN_SEASON);
        assertThat(league.statusRaw()).isEqualTo("in_season");
        assertThat(league.sport()).isEqualTo(Sport.NFL);
        assertThat(league.totalRosters()).isEqualTo(12);
        assertThat(league.draftId()).isEqualTo("1391586807681945600");
        assertThat(league.previousLeagueId()).isNull();
        assertThat(league.avatar()).isNull();
        assertThat(league.rosterPositions()).hasSize(14).startsWith("QB", "RB", "RB");
        assertThat(league.scoringSettings()).isNotEmpty().containsKey("pass_td");
        assertThat(league.settings()).isNotEmpty();
    }

    @Test
    void league_collections_are_unmodifiable() {
        stub("/v1/league/" + LEAGUE_ID, 200, Fixtures.bytes("league.json"));

        League league = client.leagues().get(LEAGUE_ID).orElseThrow();

        assertThatThrownBy(() -> league.rosterPositions().add("X"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> league.settings().put("a", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void league_with_null_collections_gets_empty_ones_and_null_setting_values_survive() {
        stub("/v1/league/1", 200,
                "{\"league_id\":\"1\",\"roster_positions\":null,\"scoring_settings\":null,"
                        + "\"settings\":{\"a\":null,\"b\":2}}");

        League league = client.leagues().get("1").orElseThrow();

        assertThat(league.rosterPositions()).isEmpty();
        assertThat(league.scoringSettings()).isEmpty();
        assertThat(league.settings()).containsEntry("a", null).containsEntry("b", 2);
    }

    @Test
    void unknown_status_maps_to_unknown_and_keeps_raw_value() {
        stub("/v1/league/1", 200, "{\"league_id\":\"1\",\"status\":\"suspended\"}");

        League league = client.leagues().get("1").orElseThrow();

        assertThat(league.status()).isEqualTo(LeagueStatus.UNKNOWN);
        assertThat(league.statusRaw()).isEqualTo("suspended");
    }

    @Test
    void league_json_404_gives_empty_optional() {
        stub("/v1/league/999", 404, "null");

        Optional<League> league = client.leagues().get("999");

        assertThat(league).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void blank_league_id_is_rejected_before_any_request(String blank) {
        assertThatThrownBy(() -> client.leagues().get(blank)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.leagues().users(blank)).isInstanceOf(IllegalArgumentException.class);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    // ---- leagues().users ----

    @Test
    void recorded_league_users_fixture_deserialises() {
        stub("/v1/league/" + LEAGUE_ID + "/users", 200, Fixtures.bytes("league_users.json"));

        List<LeagueUser> users = client.leagues().users(LEAGUE_ID);

        assertThat(users).hasSize(12);
        LeagueUser first = users.get(0);
        assertThat(first.userId()).isEqualTo("470794171712532480");
        assertThat(first.displayName()).isEqualTo("User 1");
        assertThat(first.username()).isNull();
        assertThat(first.metadata()).containsEntry("allow_pn", "on");
        assertThat(users).anySatisfy(u -> assertThat(u.teamName()).isPresent());
    }

    @Test
    void is_owner_json_key_maps_to_is_owner_component() {
        stub("/v1/league/1/users", 200,
                "[{\"user_id\":\"1\",\"is_owner\":true},{\"user_id\":\"2\",\"is_owner\":false},{\"user_id\":\"3\"}]");

        List<LeagueUser> users = client.leagues().users("1");

        assertThat(users).extracting(LeagueUser::isOwner).containsExactly(true, false, null);
    }

    @Test
    void league_users_empty_array_gives_empty_list() {
        stub("/v1/league/1/users", 200, "[]");

        assertThat(client.leagues().users("1")).isEmpty();
    }

    @Test
    void league_users_null_body_gives_empty_list() {
        stub("/v1/league/1/users", 200, "null");

        assertThat(client.leagues().users("1")).isEmpty();
    }

    @Test
    void team_name_is_present_when_metadata_has_it() {
        stub("/v1/league/1/users", 200,
                "[{\"user_id\":\"1\",\"metadata\":{\"team_name\":\"Dezpacito\"}}]");

        assertThat(client.leagues().users("1").get(0).teamName()).contains("Dezpacito");
    }

    @Test
    void team_name_is_absent_when_metadata_lacks_it_is_null_or_team_name_is_blank() {
        stub("/v1/league/1/users", 200,
                "[{\"user_id\":\"1\",\"metadata\":{\"allow_pn\":\"on\"}},"
                        + "{\"user_id\":\"2\",\"metadata\":null},{\"user_id\":\"3\"},"
                        + "{\"user_id\":\"4\",\"metadata\":{\"team_name\":\"  \"}},"
                        + "{\"user_id\":\"5\",\"metadata\":{\"team_name\":7}}]");

        List<LeagueUser> users = client.leagues().users("1");

        assertThat(users).allSatisfy(u -> assertThat(u.teamName()).isEmpty());
        assertThat(users.get(1).metadata()).isEmpty();
    }

    // ---- users().leagues ----

    @Test
    void recorded_user_leagues_fixture_deserialises_via_the_documented_path() {
        stub("/v1/user/1266175725602353152/leagues/nfl/2026", 200, Fixtures.bytes("user_leagues.json"));

        List<League> leagues = client.users().leagues("1266175725602353152", Sport.NFL, 2026);

        assertThat(leagues).hasSize(1);
        assertThat(leagues.get(0).leagueId()).isEqualTo(LEAGUE_ID);
        assertThat(leagues.get(0).name()).isEqualTo("Test League");
        wm.verify(getRequestedFor(urlEqualTo("/v1/user/1266175725602353152/leagues/nfl/2026")));
    }

    @Test
    void user_leagues_empty_array_gives_empty_list() {
        stub("/v1/user/5/leagues/nfl/2026", 200, "[]");

        assertThat(client.users().leagues("5", Sport.NFL, 2026)).isEmpty();
    }

    @Test
    void user_leagues_rejects_bad_arguments_before_any_request() {
        assertThatThrownBy(() -> client.users().leagues(" ", Sport.NFL, 2026))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.users().leagues("5", Sport.UNKNOWN, 2026))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.users().leagues("5", null, 2026))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.users().leagues("5", Sport.NFL, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.users().leagues("5", Sport.NFL, -2026))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }
}
