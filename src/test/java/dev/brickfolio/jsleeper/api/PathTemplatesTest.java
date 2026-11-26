package dev.brickfolio.jsleeper.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.brickfolio.jsleeper.RateLimit;
import dev.brickfolio.jsleeper.RequestEvent;
import dev.brickfolio.jsleeper.RequestListener;
import dev.brickfolio.jsleeper.ResponseEvent;
import dev.brickfolio.jsleeper.RetryPolicy;
import dev.brickfolio.jsleeper.SleeperClient;
import dev.brickfolio.jsleeper.error.SleeperException;
import dev.brickfolio.jsleeper.model.Sport;
import dev.brickfolio.jsleeper.model.TrendType;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every facade method reports a low-cardinality path template, and the concrete path next to it. */
class PathTemplatesTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    private static Arguments row(String name, Consumer<SleeperClient> call, String template, String path) {
        return Arguments.of(name, call, template, path);
    }

    static Stream<Arguments> endpoints() {
        return Stream.of(
                row("state", c -> c.state().get(Sport.NFL), "/state/{sport}", "/state/nfl"),
                row("users.get", c -> c.users().get("ann"), "/user/{username_or_user_id}", "/user/ann"),
                row("users.leagues", c -> c.users().leagues("7", Sport.NFL, 2026),
                        "/user/{user_id}/leagues/{sport}/{season}", "/user/7/leagues/nfl/2026"),
                row("users.drafts", c -> c.users().drafts("7", Sport.NFL, 2026),
                        "/user/{user_id}/drafts/{sport}/{season}", "/user/7/drafts/nfl/2026"),
                row("leagues.get", c -> c.leagues().get("9"), "/league/{league_id}", "/league/9"),
                row("leagues.rosters", c -> c.leagues().rosters("9"), "/league/{league_id}/rosters", "/league/9/rosters"),
                row("leagues.users", c -> c.leagues().users("9"), "/league/{league_id}/users", "/league/9/users"),
                row("leagues.matchups", c -> c.leagues().matchups("9", 3),
                        "/league/{league_id}/matchups/{week}", "/league/9/matchups/3"),
                row("leagues.winnersBracket", c -> c.leagues().winnersBracket("9"),
                        "/league/{league_id}/winners_bracket", "/league/9/winners_bracket"),
                row("leagues.losersBracket", c -> c.leagues().losersBracket("9"),
                        "/league/{league_id}/losers_bracket", "/league/9/losers_bracket"),
                row("leagues.transactions", c -> c.leagues().transactions("9", 4),
                        "/league/{league_id}/transactions/{week}", "/league/9/transactions/4"),
                row("leagues.tradedPicks", c -> c.leagues().tradedPicks("9"),
                        "/league/{league_id}/traded_picks", "/league/9/traded_picks"),
                row("leagues.drafts", c -> c.leagues().drafts("9"), "/league/{league_id}/drafts", "/league/9/drafts"),
                row("drafts.get", c -> c.drafts().get("5"), "/draft/{draft_id}", "/draft/5"),
                row("drafts.picks", c -> c.drafts().picks("5"), "/draft/{draft_id}/picks", "/draft/5/picks"),
                row("drafts.tradedPicks", c -> c.drafts().tradedPicks("5"),
                        "/draft/{draft_id}/traded_picks", "/draft/5/traded_picks"),
                row("players.all", c -> c.players().all(Sport.NFL), "/players/{sport}", "/players/nfl"),
                row("players.all(query)", c -> c.players().all(Sport.NFL, PlayerQuery.builder().position("QB").build()),
                        "/players/{sport}", "/players/nfl"),
                row("players.trending", c -> c.players().trending(Sport.NFL, TrendType.ADD),
                        "/players/{sport}/trending/{type}", "/players/nfl/trending/add"),
                row("players.trending(query)",
                        c -> c.players().trending(Sport.NFL, TrendType.DROP, TrendingQuery.builder().limit(3).build()),
                        "/players/{sport}/trending/{type}", "/players/nfl/trending/drop"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void each_endpoint_reports_its_template_and_concrete_path(
            String name, Consumer<SleeperClient> call, String template, String path) {
        wm.stubFor(get(urlMatching(".*")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("null")));
        List<RequestEvent> requests = new ArrayList<>();
        List<ResponseEvent> responses = new ArrayList<>();
        SleeperClient client = SleeperClient.builder().baseUri(wm.baseUrl() + "/v1")
                .retryPolicy(RetryPolicy.none()).rateLimit(RateLimit.disabled())
                .listener(new RequestListener() {
                    @Override
                    public void onRequest(RequestEvent event) {
                        requests.add(event);
                    }

                    @Override
                    public void onResponse(ResponseEvent event) {
                        responses.add(event);
                    }
                }).build();

        try {
            call.accept(client);
        } catch (SleeperException expectedForRequiredEndpoints) {
            // state().get treats a null body as an error; the events are still reported.
        }

        assertThat(requests).singleElement().satisfies(e -> {
            assertThat(e.pathTemplate()).isEqualTo(template);
            assertThat(e.path()).isEqualTo(path);
            assertThat(e.attempt()).isEqualTo(1);
        });
        assertThat(responses).singleElement().satisfies(e -> {
            assertThat(e.pathTemplate()).isEqualTo(template);
            assertThat(e.status()).hasValue(200);
        });
    }

    @org.junit.jupiter.api.Test
    void templates_use_only_snake_case_placeholders_and_never_the_ids() {
        assertThat(endpoints().map(a -> (String) a.get()[2]))
                .allSatisfy(t -> assertThat(t).matches("(/[a-z_]+|/\\{[a-z_]+\\})+"));
    }
}
