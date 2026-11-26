package dev.brickfolio.jsleeper.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.brickfolio.jsleeper.RateLimit;
import dev.brickfolio.jsleeper.RequestListener;
import dev.brickfolio.jsleeper.RetryPolicy;
import dev.brickfolio.jsleeper.internal.JsonCodec;
import dev.brickfolio.jsleeper.internal.RateLimiter;
import dev.brickfolio.jsleeper.internal.Requester;
import dev.brickfolio.jsleeper.internal.Sleeper;
import dev.brickfolio.jsleeper.internal.UnfilteredFetchWatch;
import dev.brickfolio.jsleeper.model.Sport;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** The unfiltered-fetch warning is wired to the right PlayersApi calls. */
class PlayersApiWarningTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final List<String> warnings = new ArrayList<>();
    private final Sleeper noSleep = d -> { };

    private PlayersApi api() {
        wm.stubFor(get(urlMatching(".*")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("null")));
        Requester requester = new Requester(HttpClient.newHttpClient(), URI.create(wm.baseUrl() + "/v1"),
                JsonCodec.create(), Duration.ofSeconds(5), "ua", RetryPolicy.none(), clock, noSleep,
                new Random(1), new RateLimiter(RateLimit.disabled(), clock, noSleep), RequestListener.noop());
        return new PlayersApi(requester, new UnfilteredFetchWatch(clock, warnings::add));
    }

    @Test
    void two_full_fetches_in_a_row_warn_exactly_once() {
        PlayersApi api = api();

        api.all(Sport.NFL);
        api.all(Sport.NFL);
        api.all(Sport.NFL);

        assertThat(warnings).hasSize(1);
    }

    @Test
    void an_empty_query_counts_as_a_full_fetch() {
        PlayersApi api = api();

        api.all(Sport.NFL);
        api.all(Sport.NFL, PlayerQuery.builder().build());

        assertThat(warnings).hasSize(1);
    }

    @Test
    void filtered_and_trending_calls_never_trigger_the_warning() {
        PlayersApi api = api();

        api.all(Sport.NFL, PlayerQuery.builder().position("QB").build());
        api.all(Sport.NFL, PlayerQuery.builder().position("QB").build());
        api.all(Sport.NFL, PlayerQuery.builder().activeOnly(true).build());
        api.all(Sport.NFL, PlayerQuery.builder().activeOnly(true).build());
        api.trending(Sport.NFL, dev.brickfolio.jsleeper.model.TrendType.ADD);
        api.trending(Sport.NFL, dev.brickfolio.jsleeper.model.TrendType.ADD);

        assertThat(warnings).isEmpty();
    }

    @Test
    void a_single_full_fetch_does_not_warn() {
        api().all(Sport.NFL);

        assertThat(warnings).isEmpty();
    }
}
