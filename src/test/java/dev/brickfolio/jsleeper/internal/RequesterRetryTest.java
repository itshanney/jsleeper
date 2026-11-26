package dev.brickfolio.jsleeper.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import dev.brickfolio.jsleeper.RetryPolicy;
import dev.brickfolio.jsleeper.error.SleeperApiException;
import dev.brickfolio.jsleeper.error.SleeperBadRequestException;
import dev.brickfolio.jsleeper.error.SleeperRateLimitException;
import dev.brickfolio.jsleeper.error.SleeperServerException;
import dev.brickfolio.jsleeper.error.SleeperTransportException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Retry behaviour of the Requester. No test here really sleeps: a recording Sleeper replaces it. */
class RequesterRetryTest {

    record Thing(String name) {}

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");  // a Thursday
    private static final String PATH = "/v1/thing";

    private final ObjectMapper mapper = JsonCodec.create();
    private final List<Duration> sleeps = new ArrayList<>();
    private final Sleeper recordingSleeper = sleeps::add;
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    /** Always returns the largest value allowed by the bound, so sleeps equal the ceilings. */
    private static final class MaxRandom implements RandomGenerator {
        @Override
        public long nextLong() {
            return Long.MAX_VALUE;
        }

        @Override
        public long nextLong(long bound) {
            return bound - 1;
        }
    }

    private Requester requester(RetryPolicy policy, Sleeper sleeper, RandomGenerator random, URI base) {
        return new Requester(HttpClient.newHttpClient(), base, mapper, Duration.ofSeconds(5), "ua",
                policy, clock, sleeper, random,
                new RateLimiter(dev.brickfolio.jsleeper.RateLimit.disabled(), clock, sleeper));
    }

    private Requester requester(RetryPolicy policy) {
        return requester(policy, recordingSleeper, new MaxRandom(), URI.create(wm.baseUrl() + "/v1"));
    }

    private Optional<Thing> call(Requester r) {
        JavaType type = mapper.constructType(Thing.class);
        return r.get(List.of("thing"), Map.of(), type, EmptyKind.OPTIONAL, null);
    }

    private void stubStatus(int status, String body) {
        wm.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private void stubSequence(String firstHeaderName, String firstHeaderValue, int firstStatus) {
        var first = aResponse().withStatus(firstStatus).withHeader("Content-Type", "application/json")
                .withBody("{}");
        if (firstHeaderName != null) {
            first = first.withHeader(firstHeaderName, firstHeaderValue);
        }
        wm.stubFor(get(urlEqualTo(PATH)).inScenario("seq").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(first).willSetStateTo("second"));
        wm.stubFor(get(urlEqualTo(PATH)).inScenario("seq").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"ok\"}")));
    }

    @Test
    void status_429_then_200_succeeds_on_the_second_attempt() {
        stubSequence(null, null, 429);

        Optional<Thing> result = call(requester(RetryPolicy.defaults()));

        assertThat(result).contains(new Thing("ok"));
        wm.verify(2, getRequestedFor(urlEqualTo(PATH)));
        assertThat(sleeps).containsExactly(Duration.ofMillis(500));
    }

    @Test
    void three_503s_throw_server_exception_after_exactly_three_requests() {
        stubStatus(503, "maintenance");

        assertThatThrownBy(() -> call(requester(RetryPolicy.defaults())))
                .isExactlyInstanceOf(SleeperServerException.class)
                .satisfies(e -> assertThat(((SleeperApiException) e).status()).isEqualTo(503));
        wm.verify(3, getRequestedFor(urlEqualTo(PATH)));
        assertThat(sleeps).hasSize(2);
    }

    @Test
    void persistent_429_throws_rate_limit_exception_after_all_attempts() {
        stubStatus(429, "slow down");

        assertThatThrownBy(() -> call(requester(RetryPolicy.defaults())))
                .isExactlyInstanceOf(SleeperRateLimitException.class);
        wm.verify(3, getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void retry_after_seconds_is_honoured_exactly() {
        stubSequence("Retry-After", "2", 503);

        Optional<Thing> result = call(requester(RetryPolicy.defaults()));

        assertThat(result).isPresent();
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2));
    }

    @Test
    void retry_after_zero_sleeps_for_zero() {
        stubSequence("Retry-After", "0", 429);

        call(requester(RetryPolicy.defaults()));

        assertThat(sleeps).containsExactly(Duration.ZERO);
    }

    @Test
    void retry_after_http_date_is_relative_to_the_injected_clock() {
        stubSequence("Retry-After", "Thu, 01 Jan 2026 00:00:10 GMT", 503);

        call(requester(RetryPolicy.defaults()));

        assertThat(sleeps).containsExactly(Duration.ofSeconds(10));
    }

    @Test
    void retry_after_http_date_in_the_past_sleeps_for_zero() {
        stubSequence("Retry-After", "Wed, 31 Dec 2025 23:59:00 GMT", 503);

        call(requester(RetryPolicy.defaults()));

        assertThat(sleeps).containsExactly(Duration.ZERO);
    }

    @Test
    void retry_after_of_sixty_seconds_is_still_honoured() {
        stubSequence("Retry-After", "60", 503);

        call(requester(RetryPolicy.defaults()));

        assertThat(sleeps).containsExactly(Duration.ofSeconds(60));
    }

    @Test
    void retry_after_above_sixty_seconds_throws_immediately_without_sleeping() {
        wm.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(429)
                .withHeader("Retry-After", "120").withHeader("Content-Type", "application/json").withBody("x")));

        assertThatThrownBy(() -> call(requester(RetryPolicy.defaults())))
                .isExactlyInstanceOf(SleeperRateLimitException.class);
        wm.verify(1, getRequestedFor(urlEqualTo(PATH)));
        assertThat(sleeps).isEmpty();
    }

    @Test
    void unparseable_retry_after_falls_back_to_jittered_backoff() {
        stubSequence("Retry-After", "soon", 503);

        call(requester(RetryPolicy.defaults()));

        assertThat(sleeps).containsExactly(Duration.ofMillis(500));
    }

    @Test
    void status_400_is_not_retried() {
        stubStatus(400, "bad");

        assertThatThrownBy(() -> call(requester(RetryPolicy.defaults())))
                .isExactlyInstanceOf(SleeperBadRequestException.class);
        wm.verify(1, getRequestedFor(urlEqualTo(PATH)));
        assertThat(sleeps).isEmpty();
    }

    @Test
    void html_404_and_json_404_are_not_retried() {
        wm.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "text/html").withBody("<html/>")));
        assertThatThrownBy(() -> call(requester(RetryPolicy.defaults())))
                .isExactlyInstanceOf(SleeperApiException.class);
        wm.verify(1, getRequestedFor(urlEqualTo(PATH)));

        wm.resetRequests();
        stubStatus(404, "null");
        assertThat(call(requester(RetryPolicy.defaults()))).isEmpty();
        wm.verify(1, getRequestedFor(urlEqualTo(PATH)));
        assertThat(sleeps).isEmpty();
    }

    @Test
    void deserialisation_failure_is_not_retried() {
        stubStatus(200, "{\"name\": ");

        assertThatThrownBy(() -> call(requester(RetryPolicy.defaults())))
                .isExactlyInstanceOf(dev.brickfolio.jsleeper.error.SleeperDeserializationException.class);
        wm.verify(1, getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void none_makes_exactly_one_request_even_on_503() {
        stubStatus(503, "down");

        assertThatThrownBy(() -> call(requester(RetryPolicy.none())))
                .isExactlyInstanceOf(SleeperServerException.class);
        wm.verify(1, getRequestedFor(urlEqualTo(PATH)));
        assertThat(sleeps).isEmpty();
    }

    @Test
    void custom_status_set_replaces_the_defaults() {
        wm.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(418).withBody("tea")));
        RetryPolicy teapot = RetryPolicy.builder().retryOn(Set.of(418)).build();

        assertThatThrownBy(() -> call(requester(teapot))).isExactlyInstanceOf(SleeperApiException.class);
        wm.verify(3, getRequestedFor(urlEqualTo(PATH)));

        wm.resetRequests();
        stubStatus(503, "down");
        assertThatThrownBy(() -> call(requester(teapot))).isExactlyInstanceOf(SleeperServerException.class);
        wm.verify(1, getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void connection_refused_is_retried_then_throws_transport_exception() throws IOException {
        URI closed = closedPortUri();

        assertThatThrownBy(() -> call(requester(RetryPolicy.defaults(), recordingSleeper, new MaxRandom(), closed)))
                .isExactlyInstanceOf(SleeperTransportException.class);
        assertThat(sleeps).hasSize(2);
    }

    @Test
    void transport_errors_are_not_retried_when_disabled() throws IOException {
        URI closed = closedPortUri();
        RetryPolicy noTransport = RetryPolicy.builder().retryOnTransportErrors(false).build();

        assertThatThrownBy(() -> call(requester(noTransport, recordingSleeper, new MaxRandom(), closed)))
                .isExactlyInstanceOf(SleeperTransportException.class);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void jitter_ceilings_double_then_cap_at_max_backoff() {
        stubStatus(503, "down");
        RetryPolicy six = RetryPolicy.builder().maxAttempts(7).build();

        assertThatThrownBy(() -> call(requester(six))).isInstanceOf(SleeperServerException.class);

        assertThat(sleeps).containsExactly(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ofSeconds(4), Duration.ofSeconds(8), Duration.ofSeconds(8));
    }

    @Test
    void seeded_random_gives_deterministic_values_within_bounds() {
        stubStatus(503, "down");
        RetryPolicy five = RetryPolicy.builder().maxAttempts(5).build();
        Random seeded = new Random(42);

        assertThatThrownBy(() -> call(requester(five, recordingSleeper, seeded, URI.create(wm.baseUrl() + "/v1"))))
                .isInstanceOf(SleeperServerException.class);

        Random replay = new Random(42);
        for (int retry = 1; retry <= 4; retry++) {
            long ceiling = five.backoffCeiling(retry).toMillis();
            Duration actual = sleeps.get(retry - 1);
            assertThat(actual).isEqualTo(Duration.ofMillis(replay.nextLong(ceiling + 1)));
            assertThat(actual).isBetween(Duration.ZERO, Duration.ofMillis(ceiling));
        }
    }

    @Test
    void custom_cap_limits_every_sleep() {
        stubStatus(503, "down");
        RetryPolicy capped = RetryPolicy.builder().maxAttempts(6).initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofMillis(250)).build();

        assertThatThrownBy(() -> call(requester(capped))).isInstanceOf(SleeperServerException.class);

        assertThat(sleeps).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200),
                Duration.ofMillis(250), Duration.ofMillis(250), Duration.ofMillis(250));
    }

    @Test
    void interrupt_during_sleep_restores_flag_and_throws_transport_exception() {
        stubStatus(503, "down");
        Sleeper interrupting = d -> {
            throw new InterruptedException("stop");
        };
        Requester r = requester(RetryPolicy.defaults(), interrupting, new MaxRandom(), URI.create(wm.baseUrl() + "/v1"));

        try {
            assertThatThrownBy(() -> call(r))
                    .isExactlyInstanceOf(SleeperTransportException.class)
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        wm.verify(1, getRequestedFor(urlEqualTo(PATH)));
    }

    private static URI closedPortUri() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return URI.create("http://localhost:" + s.getLocalPort());
        }
    }
}
