package dev.brickfolio.jsleeper.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.brickfolio.jsleeper.RateLimit;
import dev.brickfolio.jsleeper.RequestEvent;
import dev.brickfolio.jsleeper.RequestListener;
import dev.brickfolio.jsleeper.ResponseEvent;
import dev.brickfolio.jsleeper.RetryPolicy;
import dev.brickfolio.jsleeper.error.SleeperServerException;
import dev.brickfolio.jsleeper.error.SleeperTransportException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class RequestListenerTest {

    record Thing(String name) {}

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    private static final String TEMPLATE = "/thing/{id}";

    private final ObjectMapper mapper = JsonCodec.create();
    private final Sleeper noSleep = d -> { };

    private static final class Recorder implements RequestListener {
        final List<RequestEvent> requests = new ArrayList<>();
        final List<ResponseEvent> responses = new ArrayList<>();

        @Override
        public void onRequest(RequestEvent event) {
            requests.add(event);
        }

        @Override
        public void onResponse(ResponseEvent event) {
            responses.add(event);
        }
    }

    private Requester requester(URI base, RetryPolicy policy, RequestListener listener) {
        return new Requester(HttpClient.newHttpClient(), base, mapper, Duration.ofSeconds(5), "ua", policy,
                Clock.systemUTC(), noSleep, new Random(1),
                new RateLimiter(RateLimit.disabled(), Clock.systemUTC(), noSleep), listener);
    }

    private Optional<Thing> call(Requester r) {
        return r.get(TEMPLATE, List.of("thing", "42"), Map.of(), mapper.constructType(Thing.class),
                EmptyKind.OPTIONAL, null);
    }

    private void stub(int status, String body) {
        wm.stubFor(get(urlEqualTo("/v1/thing/42")).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private URI base() {
        return URI.create(wm.baseUrl() + "/v1");
    }

    @Test
    void three_503s_produce_three_request_and_three_response_events_with_retry_flags_and_reasons() {
        String body = "down for maintenance";
        stub(503, body);
        Recorder rec = new Recorder();

        assertThatThrownBy(() -> call(requester(base(), RetryPolicy.defaults(), rec)))
                .isExactlyInstanceOf(SleeperServerException.class);

        assertThat(rec.requests).extracting(RequestEvent::attempt).containsExactly(1, 2, 3);
        assertThat(rec.requests).allSatisfy(e -> {
            assertThat(e.method()).isEqualTo("GET");
            assertThat(e.pathTemplate()).isEqualTo(TEMPLATE);
            assertThat(e.path()).isEqualTo("/thing/42");
        });
        assertThat(rec.responses).extracting(ResponseEvent::attempt).containsExactly(1, 2, 3);
        assertThat(rec.responses).extracting(ResponseEvent::willRetry).containsExactly(true, true, false);
        assertThat(rec.responses).extracting(ResponseEvent::retryReason)
                .containsExactly(Optional.of("status 503"), Optional.of("status 503"), Optional.empty());
        assertThat(rec.responses).allSatisfy(e -> {
            assertThat(e.status()).hasValue(503);
            assertThat(e.bytes()).isEqualTo(body.length());
            assertThat(e.duration()).isGreaterThanOrEqualTo(Duration.ZERO);
            assertThat(e.error()).containsInstanceOf(SleeperServerException.class);
            assertThat(e.pathTemplate()).isEqualTo(TEMPLATE);
        });
    }

    @Test
    void a_successful_call_has_one_event_pair_with_no_error_and_no_retry() {
        String body = "{\"name\":\"ok\"}";
        stub(200, body);
        Recorder rec = new Recorder();

        Optional<Thing> result = call(requester(base(), RetryPolicy.defaults(), rec));

        assertThat(result).contains(new Thing("ok"));
        assertThat(rec.requests).hasSize(1);
        ResponseEvent e = rec.responses.get(0);
        assertThat(e.status()).hasValue(200);
        assertThat(e.bytes()).isEqualTo(body.length());
        assertThat(e.error()).isEmpty();
        assertThat(e.willRetry()).isFalse();
        assertThat(e.retryReason()).isEmpty();
    }

    @Test
    void a_json_404_is_reported_with_its_status_and_no_error() {
        stub(404, "null");
        Recorder rec = new Recorder();

        assertThat(call(requester(base(), RetryPolicy.defaults(), rec))).isEmpty();

        assertThat(rec.responses).singleElement().satisfies(e -> {
            assertThat(e.status()).hasValue(404);
            assertThat(e.error()).isEmpty();
        });
    }

    @Test
    void a_non_retryable_failure_is_reported_once_with_will_retry_false() {
        stub(400, "bad");
        Recorder rec = new Recorder();

        assertThatThrownBy(() -> call(requester(base(), RetryPolicy.defaults(), rec)))
                .isInstanceOf(dev.brickfolio.jsleeper.error.SleeperBadRequestException.class);

        assertThat(rec.responses).singleElement().satisfies(e -> {
            assertThat(e.willRetry()).isFalse();
            assertThat(e.error()).isPresent();
        });
    }

    @Test
    void a_transport_failure_has_an_empty_status_and_a_transport_retry_reason() throws IOException {
        URI closed;
        try (ServerSocket s = new ServerSocket(0)) {
            closed = URI.create("http://localhost:" + s.getLocalPort());
        }
        Recorder rec = new Recorder();

        assertThatThrownBy(() -> call(requester(closed, RetryPolicy.defaults(), rec)))
                .isExactlyInstanceOf(SleeperTransportException.class);

        assertThat(rec.responses).hasSize(3);
        assertThat(rec.responses).allSatisfy(e -> {
            assertThat(e.status()).isEmpty();
            assertThat(e.bytes()).isZero();
            assertThat(e.error()).containsInstanceOf(SleeperTransportException.class);
        });
        assertThat(rec.responses).extracting(ResponseEvent::retryReason)
                .containsExactly(Optional.of("transport"), Optional.of("transport"), Optional.empty());
    }

    @Test
    void a_throwing_listener_does_not_break_a_successful_call() {
        stub(200, "{\"name\":\"ok\"}");
        RequestListener throwing = new RequestListener() {
            @Override
            public void onRequest(RequestEvent event) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void onResponse(ResponseEvent event) {
                throw new IllegalStateException("boom");
            }
        };

        Optional<Thing> result = call(requester(base(), RetryPolicy.defaults(), throwing));

        assertThat(result).contains(new Thing("ok"));
    }

    @Test
    void a_throwing_listener_does_not_change_the_failure_or_stop_retries() {
        stub(503, "down");
        int[] calls = {0};
        RequestListener throwing = new RequestListener() {
            @Override
            public void onResponse(ResponseEvent event) {
                calls[0]++;
                throw new IllegalStateException("boom");
            }
        };

        assertThatThrownBy(() -> call(requester(base(), RetryPolicy.defaults(), throwing)))
                .isExactlyInstanceOf(SleeperServerException.class);
        assertThat(calls[0]).isEqualTo(3);
    }

    @Test
    void the_default_listener_does_nothing() {
        RequestListener noop = RequestListener.noop();

        noop.onRequest(new RequestEvent("GET", "/x", "/x", 1));
        noop.onResponse(new ResponseEvent("GET", "/x", "/x", 1, java.util.OptionalInt.empty(), Duration.ZERO, 0,
                Optional.empty(), false, Optional.empty()));
    }
}
