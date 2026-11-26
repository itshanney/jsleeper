package dev.brickfolio.jsleeper.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.brickfolio.jsleeper.RateLimit;
import dev.brickfolio.jsleeper.RetryPolicy;
import dev.brickfolio.jsleeper.error.SleeperRateLimitException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** The limiter as wired into the Requester. */
class RequesterRateLimitTest {

    record Thing(String name) {}

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    private static final String PATH = "/v1/thing";

    private final ObjectMapper mapper = JsonCodec.create();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final List<Duration> sleeps = new ArrayList<>();
    private final Sleeper sleeper = sleeps::add;

    private Requester requester(RateLimit limit, RetryPolicy retry) {
        return new Requester(HttpClient.newHttpClient(), URI.create(wm.baseUrl() + "/v1"), mapper,
                Duration.ofSeconds(5), "ua", retry, clock, sleeper, new Random(1),
                new RateLimiter(limit, clock, sleeper));
    }

    private Optional<Thing> call(Requester r) {
        return r.get(List.of("thing"), Map.of(), mapper.constructType(Thing.class), EmptyKind.OPTIONAL, null);
    }

    private void stub(int status, String body) {
        wm.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    void a_rejected_wait_sends_no_request_and_reports_a_client_side_429() {
        stub(200, "{}");
        Requester r = requester(RateLimit.perMinute(60).burst(1).maxWait(Duration.ZERO), RetryPolicy.none());
        call(r);
        wm.resetRequests();

        assertThatThrownBy(() -> call(r))
                .isExactlyInstanceOf(SleeperRateLimitException.class)
                .satisfies(e -> {
                    assertThat(((SleeperRateLimitException) e).isClientSide()).isTrue();
                    assertThat(((SleeperRateLimitException) e).status()).isEqualTo(429);
                });
        wm.verify(0, getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void a_wait_within_max_wait_sleeps_then_sends_the_request() {
        stub(200, "{\"name\":\"ok\"}");
        Requester r = requester(RateLimit.perMinute(60).burst(1), RetryPolicy.none());
        call(r);

        Optional<Thing> second = call(r);

        assertThat(second).contains(new Thing("ok"));
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
        wm.verify(2, getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void every_retry_attempt_takes_a_token() {
        stub(503, "down");
        // Burst of 2 with no wait allowed: attempts 1 and 2 consume the tokens, attempt 3 is refused.
        Requester r = requester(RateLimit.perMinute(60).burst(2).maxWait(Duration.ZERO),
                RetryPolicy.builder().maxAttempts(3).build());

        assertThatThrownBy(() -> call(r))
                .isExactlyInstanceOf(SleeperRateLimitException.class)
                .satisfies(e -> assertThat(((SleeperRateLimitException) e).isClientSide()).isTrue());

        wm.verify(2, getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void disabled_limit_never_slows_requests() {
        stub(200, "{}");
        Requester r = requester(RateLimit.disabled(), RetryPolicy.none());

        for (int i = 0; i < 100; i++) {
            call(r);
        }

        assertThat(sleeps).isEmpty();
    }
}
