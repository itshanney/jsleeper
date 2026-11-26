package dev.brickfolio.jsleeper.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.brickfolio.jsleeper.Fixtures;
import dev.brickfolio.jsleeper.SleeperClient;
import dev.brickfolio.jsleeper.model.Roster;
import dev.brickfolio.jsleeper.model.RosterSettings;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class RostersApiTest {

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

    @Test
    void recorded_rosters_fixture_deserialises() {
        stub("/v1/league/L/rosters", Fixtures.bytes("league_rosters.json"));

        List<Roster> rosters = client.leagues().rosters("L");

        assertThat(rosters).hasSize(12);
        Roster first = rosters.get(0);
        assertThat(first.rosterId()).isEqualTo(1);
        assertThat(first.leagueId()).isEqualTo("1391586806968889344");
        assertThat(first.ownerId()).isNotBlank();
        assertThat(first.players()).isNotEmpty();
        assertThat(first.starters()).isNotEmpty();
        assertThat(first.reserve()).containsExactly("8121");
        assertThat(first.taxi()).isEmpty();
        assertThat(first.coOwners()).isEmpty();
        RosterSettings s = first.settings();
        assertThat(s.wins()).isEqualTo(8);
        assertThat(s.losses()).isEqualTo(3);
        assertThat(s.ties()).isZero();
        assertThat(s.waiverPosition()).isEqualTo(10);
        assertThat(s.waiverBudgetUsed()).isEqualTo(18);
        assertThat(s.division()).isEqualTo(1);
        assertThat(s.pointsFor()).isEqualTo(new BigDecimal("1286.40"));
        assertThat(s.pointsAgainst()).isEqualTo(new BigDecimal("1119.32"));
        assertThat(s.potentialPoints()).isEqualTo(new BigDecimal("1437.26"));
    }

    @Test
    void null_owner_is_tolerated_for_an_orphaned_team() {
        stub("/v1/league/L/rosters", "[{\"roster_id\":3,\"owner_id\":null}]");

        Roster r = client.leagues().rosters("L").get(0);

        assertThat(r.ownerId()).isNull();
        assertThat(r.rosterId()).isEqualTo(3);
    }

    @Test
    void null_settings_and_lists_become_safe_defaults() {
        stub("/v1/league/L/rosters",
                "[{\"roster_id\":1,\"settings\":null,\"players\":null,\"starters\":null,"
                        + "\"reserve\":null,\"taxi\":null,\"co_owners\":null}]");

        Roster r = client.leagues().rosters("L").get(0);

        assertThat(r.settings().wins()).isNull();
        assertThat(r.settings().pointsFor()).isEqualTo(new BigDecimal("0.00"));
        assertThat(r.players()).isEmpty();
        assertThat(r.starters()).isEmpty();
        assertThat(r.reserve()).isEmpty();
        assertThatThrownBy(() -> r.players().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void missing_settings_key_also_gives_safe_settings() {
        stub("/v1/league/L/rosters", "[{\"roster_id\":1}]");

        assertThat(client.leagues().rosters("L").get(0).settings().wins()).isNull();
    }

    @Test
    void empty_and_null_bodies_give_empty_list() {
        stub("/v1/league/A/rosters", "[]");
        stub("/v1/league/B/rosters", "null");

        assertThat(client.leagues().rosters("A")).isEmpty();
        assertThat(client.leagues().rosters("B")).isEmpty();
    }

    @Test
    void blank_league_id_is_rejected_before_any_request() {
        assertThatThrownBy(() -> client.leagues().rosters(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void usage_example_standings_comparator_sorts_by_wins_then_points_for() {
        stub("/v1/league/L/rosters",
                "[{\"roster_id\":1,\"settings\":{\"wins\":5,\"fpts\":100,\"fpts_decimal\":50}},"
                        + "{\"roster_id\":2,\"settings\":{\"wins\":7,\"fpts\":90,\"fpts_decimal\":5}},"
                        + "{\"roster_id\":3,\"settings\":{\"wins\":5,\"fpts\":100,\"fpts_decimal\":75}},"
                        + "{\"roster_id\":4,\"settings\":{\"wins\":5,\"fpts\":99}}]");

        // Usage Examples section 3, verbatim apart from the source of the list.
        List<Roster> standings = client.leagues().rosters("L").stream()
                .sorted(Comparator
                        .comparing((Roster r) -> r.settings().wins()).reversed()
                        .thenComparing(r -> r.settings().pointsFor(), Comparator.reverseOrder()))
                .toList();

        assertThat(standings).extracting(Roster::rosterId).containsExactly(2, 3, 1, 4);
    }
}
