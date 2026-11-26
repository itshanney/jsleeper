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
import dev.brickfolio.jsleeper.RateLimit;
import dev.brickfolio.jsleeper.RequestEvent;
import dev.brickfolio.jsleeper.RequestListener;
import dev.brickfolio.jsleeper.RetryPolicy;
import dev.brickfolio.jsleeper.SleeperClient;
import dev.brickfolio.jsleeper.model.League;
import dev.brickfolio.jsleeper.model.Matchup;
import dev.brickfolio.jsleeper.model.Sport;
import dev.brickfolio.jsleeper.model.SportState;
import dev.brickfolio.jsleeper.model.Transaction;
import dev.brickfolio.jsleeper.model.User;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ConvenienceOverloadsTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    private final List<String> templates = new ArrayList<>();
    private final SleeperClient client = SleeperClient.builder().baseUri(wm.baseUrl() + "/v1")
            .retryPolicy(RetryPolicy.none()).rateLimit(RateLimit.disabled())
            .listener(new RequestListener() {
                @Override
                public void onRequest(RequestEvent event) {
                    templates.add(event.pathTemplate());
                }
            }).build();

    private void stub(String url, String body) {
        wm.stubFor(get(urlEqualTo(url)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private void stub(String url, byte[] body) {
        wm.stubFor(get(urlEqualTo(url)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private void stubState(String stateJsonFields) {
        stub("/v1/state/nfl", "{" + stateJsonFields + "}");
    }

    // ---- week-defaulting overloads ----

    @Test
    void matchups_without_week_calls_state_then_uses_display_week_not_week() {
        stubState("\"week\":11,\"display_week\":12");
        stub("/v1/league/L/matchups/12", Fixtures.bytes("league_matchups_w1.json"));

        List<Matchup> rows = client.leagues().matchups("L");

        assertThat(rows).hasSize(12);
        assertThat(templates).containsExactly("/state/{sport}", "/league/{league_id}/matchups/{week}");
        wm.verify(1, getRequestedFor(urlEqualTo("/v1/state/nfl")));
        wm.verify(1, getRequestedFor(urlEqualTo("/v1/league/L/matchups/12")));
        wm.verify(0, getRequestedFor(urlEqualTo("/v1/league/L/matchups/11")));
    }

    @Test
    void transactions_without_week_calls_state_then_uses_display_week_not_week() {
        stubState("\"week\":11,\"display_week\":12");
        stub("/v1/league/L/transactions/12", Fixtures.bytes("league_transactions_w1.json"));

        List<Transaction> txs = client.leagues().transactions("L");

        assertThat(txs).hasSize(16);
        assertThat(templates).containsExactly("/state/{sport}", "/league/{league_id}/transactions/{week}");
        wm.verify(0, getRequestedFor(urlEqualTo("/v1/league/L/transactions/11")));
    }

    @Test
    void null_or_zero_display_week_throws_illegal_state_and_makes_no_second_call() {
        stubState("\"week\":0,\"display_week\":null");
        assertThatThrownBy(() -> client.leagues().matchups("L"))
                .isExactlyInstanceOf(IllegalStateException.class).hasMessageContaining("display_week");

        stubState("\"week\":0,\"display_week\":0");
        assertThatThrownBy(() -> client.leagues().transactions("L"))
                .isExactlyInstanceOf(IllegalStateException.class);

        stubState("\"week\":3");
        assertThatThrownBy(() -> client.leagues().matchups("L")).isInstanceOf(IllegalStateException.class);

        assertThat(templates).containsOnly("/state/{sport}");
    }

    @Test
    void blank_league_id_is_rejected_before_any_request() {
        assertThatThrownBy(() -> client.leagues().matchups(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.leagues().transactions((String) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    // ---- string season overloads ----

    @Test
    void string_season_leagues_hits_the_same_path_as_the_int_overload() {
        stub("/v1/user/U/leagues/nfl/2026", Fixtures.bytes("user_leagues.json"));

        List<League> leagues = client.users().leagues("U", Sport.NFL, "2026");

        assertThat(leagues).hasSize(1);
        wm.verify(1, getRequestedFor(urlEqualTo("/v1/user/U/leagues/nfl/2026")));
    }

    @Test
    void string_season_drafts_hits_the_same_path_as_the_int_overload() {
        stub("/v1/user/U/drafts/nfl/2026", Fixtures.bytes("user_drafts.json"));

        assertThat(client.users().drafts("U", Sport.NFL, " 2026 ")).hasSize(2);
        wm.verify(1, getRequestedFor(urlEqualTo("/v1/user/U/drafts/nfl/2026")));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "abc", "20x6", "2026.5", "0", "-2026"})
    void bad_string_seasons_are_rejected_before_any_request(String bad) {
        assertThatThrownBy(() -> client.users().leagues("U", Sport.NFL, bad))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.users().drafts("U", Sport.NFL, bad))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void non_numeric_season_message_names_the_value() {
        assertThatThrownBy(() -> client.users().leagues("U", Sport.NFL, "abc"))
                .hasMessageContaining("abc");
    }

    // ---- Usage Examples section 2 ----

    @Test
    void usage_example_username_to_this_seasons_leagues_compiles_and_runs() {
        stub("/v1/user/someuser", Fixtures.bytes("user.json"));
        stub("/v1/state/nfl", Fixtures.bytes("state_nfl.json"));
        stub("/v1/user/1266175725602353152/leagues/nfl/2026", Fixtures.bytes("user_leagues.json"));
        SleeperClient sleeper = client;
        List<String> out = new ArrayList<>();

        // Usage Examples section 2 (System.out.printf replaced by a list).
        User user = sleeper.users().get("someuser")
                .orElseThrow(() -> new IllegalArgumentException("No such Sleeper user"));

        SportState nfl = sleeper.state().get(Sport.NFL);

        List<League> leagues = sleeper.users().leagues(user.userId(), Sport.NFL, nfl.leagueSeason());

        for (League league : leagues) {
            out.add(String.format("%s  [%s]  %d teams  status=%s%n",
                    league.name(), league.leagueId(), league.totalRosters(), league.status()));
        }

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).contains("Test League").contains("12 teams").contains("IN_SEASON");
    }
}
