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
import dev.brickfolio.jsleeper.model.Matchup;
import dev.brickfolio.jsleeper.model.MatchupPair;
import dev.brickfolio.jsleeper.util.Matchups;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class MatchupsApiTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    private final SleeperClient client = SleeperClient.builder().baseUri(wm.baseUrl() + "/v1").build();

    private void stub(String url, byte[] body) {
        wm.stubFor(get(urlEqualTo(url)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    void recorded_week_fixture_gives_twelve_rows_and_six_pairs() {
        stub("/v1/league/L/matchups/1", Fixtures.bytes("league_matchups_w1.json"));

        List<Matchup> rows = client.leagues().matchups("L", 1);
        List<MatchupPair> pairs = Matchups.pair(rows);

        assertThat(rows).hasSize(12);
        assertThat(pairs).hasSize(6).allSatisfy(p -> assertThat(p.away()).isNotNull());
        Matchup first = rows.get(0);
        assertThat(first.rosterId()).isEqualTo(1);
        assertThat(first.matchupId()).isEqualTo(6);
        assertThat(first.points()).isEqualTo(130.86);
        assertThat(first.customPoints()).isNull();
        assertThat(first.players()).hasSize(14);
        assertThat(first.starters()).hasSize(9);
        assertThat(first.startersPoints()).hasSize(9).startsWith(14.26, 18.8, 24.2);
        assertThat(first.playersPoints()).containsEntry("12501", 15.5);
        assertThat(first.bench()).hasSize(5).doesNotContainAnyElementsOf(first.starters());
        assertThat(pairs.get(0).home().rosterId()).isEqualTo(1);
        assertThat(pairs.get(0).away().rosterId()).isEqualTo(9);
    }

    @Test
    void hand_made_edge_fixture_covers_bye_and_custom_points() {
        stub("/v1/league/L/matchups/2", Fixtures.bytes("edge_matchups_bye_custom.json"));

        List<Matchup> rows = client.leagues().matchups("L", 2);
        List<MatchupPair> pairs = Matchups.pair(rows);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).customPoints()).isEqualTo(150.5);
        assertThat(rows.get(0).effectivePoints()).isEqualTo(150.5);
        assertThat(rows.get(1).effectivePoints()).isEqualTo(rows.get(1).points());
        assertThat(rows.get(2).matchupId()).isNull();
        assertThat(pairs).hasSize(2);
        assertThat(pairs.get(0).away()).isNotNull();
        assertThat(pairs.get(1).home().rosterId()).isEqualTo(7);
        assertThat(pairs.get(1).away()).isNull();
    }

    @Test
    void week_is_sent_in_the_path() {
        stub("/v1/league/L/matchups/12", "[]".getBytes());

        assertThat(client.leagues().matchups("L", 12)).isEmpty();
        wm.verify(getRequestedFor(urlEqualTo("/v1/league/L/matchups/12")));
    }

    @Test
    void week_below_one_and_blank_league_are_rejected_before_any_request() {
        assertThatThrownBy(() -> client.leagues().matchups("L", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.leagues().matchups("L", -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.leagues().matchups(" ", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }
}
