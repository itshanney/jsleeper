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
import dev.brickfolio.jsleeper.RequestListener;
import dev.brickfolio.jsleeper.RetryPolicy;
import dev.brickfolio.jsleeper.error.SleeperApiException;
import dev.brickfolio.jsleeper.error.SleeperBadRequestException;
import dev.brickfolio.jsleeper.error.SleeperDeserializationException;
import dev.brickfolio.jsleeper.error.SleeperRateLimitException;
import dev.brickfolio.jsleeper.error.SleeperServerException;
import dev.brickfolio.jsleeper.error.SleeperTransportException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class RequesterTest {

    record Thing(String name, Integer totalRosters) {}

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    private static final String USER_AGENT = "jsleeper-test/1.0";
    private static final String HTML_404 = "<!doctype html><html><body>Sleeper app</body></html>";

    private final ObjectMapper mapper = JsonCodec.create();
    private final HttpClient http = HttpClient.newHttpClient();

    private Requester requester(URI base, Duration timeout) {
        return new Requester(http, base, mapper, timeout, USER_AGENT, RetryPolicy.none(),
                java.time.Clock.systemUTC(), Sleeper.real(), new java.util.Random(1),
                new RateLimiter(dev.brickfolio.jsleeper.RateLimit.disabled(), java.time.Clock.systemUTC(), Sleeper.real()), RequestListener.noop());
    }

    private Requester requester() {
        return requester(URI.create(wm.baseUrl() + "/v1"), Duration.ofSeconds(5));
    }

    private JavaType type(Class<?> c) {
        return mapper.constructType(c);
    }

    private JavaType listOf(Class<?> c) {
        return mapper.getTypeFactory().constructCollectionType(List.class, c);
    }

    private JavaType mapOf(Class<?> c) {
        return mapper.getTypeFactory().constructMapType(Map.class, String.class, c);
    }

    private void stubJson(String path, int status, String body) {
        wm.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private <R> R call(String path, JavaType type, EmptyKind kind) {
        return requester().get("/t", List.of(path), Map.of(), type, kind, null);
    }

    // ---- 200 responses ----

    @Test
    void ok_object_is_deserialised_with_snake_case_and_wrapped_for_optional() {
        stubJson("/v1/thing", 200, "{\"name\":\"a\",\"total_rosters\":12}");

        Optional<Thing> result = call("thing", type(Thing.class), EmptyKind.OPTIONAL);

        assertThat(result).contains(new Thing("a", 12));
    }

    @Test
    void ok_null_body_gives_empty_optional() {
        stubJson("/v1/thing", 200, "null");

        Optional<Thing> result = call("thing", type(Thing.class), EmptyKind.OPTIONAL);

        assertThat(result).isEmpty();
    }

    @Test
    void ok_null_body_gives_empty_list() {
        stubJson("/v1/thing", 200, "null");

        List<Thing> result = call("thing", listOf(Thing.class), EmptyKind.LIST);

        assertThat(result).isEmpty();
    }

    @Test
    void ok_null_body_gives_empty_map() {
        stubJson("/v1/thing", 200, "null");

        Map<String, Thing> result = call("thing", mapOf(Thing.class), EmptyKind.MAP);

        assertThat(result).isEmpty();
    }

    @Test
    void ok_null_body_throws_api_exception_for_required() {
        stubJson("/v1/thing", 200, "null");

        assertThatThrownBy(() -> call("thing", type(Thing.class), EmptyKind.REQUIRED))
                .isExactlyInstanceOf(SleeperApiException.class)
                .satisfies(e -> {
                    var api = (SleeperApiException) e;
                    assertThat(api.status()).isEqualTo(200);
                    assertThat(api.path()).isEqualTo("/thing");
                    assertThat(api.method()).isEqualTo("GET");
                });
    }

    @Test
    void ok_empty_array_gives_empty_list() {
        stubJson("/v1/thing", 200, "[]");

        List<Thing> result = call("thing", listOf(Thing.class), EmptyKind.LIST);

        assertThat(result).isEmpty();
    }

    @Test
    void ok_array_is_deserialised_into_list() {
        stubJson("/v1/thing", 200, "[{\"name\":\"a\"},{\"name\":\"b\"}]");

        List<Thing> result = call("thing", listOf(Thing.class), EmptyKind.LIST);

        assertThat(result).extracting(Thing::name).containsExactly("a", "b");
    }

    @Test
    void ok_object_is_deserialised_into_map() {
        stubJson("/v1/thing", 200, "{\"DET\":{\"name\":\"d\"}}");

        Map<String, Thing> result = call("thing", mapOf(Thing.class), EmptyKind.MAP);

        assertThat(result).containsOnlyKeys("DET");
    }

    @Test
    void ok_empty_body_gives_empty_result() {
        stubJson("/v1/thing", 200, "");

        Optional<Thing> result = call("thing", type(Thing.class), EmptyKind.OPTIONAL);

        assertThat(result).isEmpty();
    }

    @Test
    void ok_whitespace_only_body_gives_empty_result() {
        stubJson("/v1/thing", 200, " \n\t ");

        List<Thing> result = call("thing", listOf(Thing.class), EmptyKind.LIST);

        assertThat(result).isEmpty();
    }

    // ---- 404 ----

    @Test
    void json_404_gives_empty_result_for_each_non_required_kind() {
        stubJson("/v1/thing", 404, "null");

        Optional<Thing> opt = call("thing", type(Thing.class), EmptyKind.OPTIONAL);
        List<Thing> list = call("thing", listOf(Thing.class), EmptyKind.LIST);
        Map<String, Thing> map = call("thing", mapOf(Thing.class), EmptyKind.MAP);

        assertThat(opt).isEmpty();
        assertThat(list).isEmpty();
        assertThat(map).isEmpty();
    }

    @Test
    void json_404_with_charset_parameter_is_still_json() {
        wm.stubFor(get(urlEqualTo("/v1/thing")).willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/json; charset=utf-8").withBody("null")));

        Optional<Thing> result = call("thing", type(Thing.class), EmptyKind.OPTIONAL);

        assertThat(result).isEmpty();
    }

    @Test
    void json_404_throws_404_api_exception_for_required() {
        stubJson("/v1/thing", 404, "null");

        assertThatThrownBy(() -> call("thing", type(Thing.class), EmptyKind.REQUIRED))
                .isExactlyInstanceOf(SleeperApiException.class)
                .extracting(e -> ((SleeperApiException) e).status()).isEqualTo(404);
    }

    @Test
    void html_404_throws_api_exception_even_for_optional() {
        wm.stubFor(get(urlEqualTo("/v1/bogus")).willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "text/html").withBody(HTML_404)));

        assertThatThrownBy(() -> call("bogus", type(Thing.class), EmptyKind.OPTIONAL))
                .isExactlyInstanceOf(SleeperApiException.class)
                .satisfies(e -> {
                    var api = (SleeperApiException) e;
                    assertThat(api.status()).isEqualTo(404);
                    assertThat(api.bodySnippet()).contains("Sleeper app");
                });
    }

    @Test
    void html_404_throws_for_list_kind_too() {
        wm.stubFor(get(urlEqualTo("/v1/bogus")).willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "text/html").withBody(HTML_404)));

        assertThatThrownBy(() -> call("bogus", listOf(Thing.class), EmptyKind.LIST))
                .isExactlyInstanceOf(SleeperApiException.class);
    }

    @Test
    void response_404_without_content_type_is_treated_as_unknown_route() {
        wm.stubFor(get(urlEqualTo("/v1/bogus")).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> call("bogus", type(Thing.class), EmptyKind.OPTIONAL))
                .isExactlyInstanceOf(SleeperApiException.class);
    }

    // ---- other statuses ----

    @Test
    void status_400_throws_bad_request() {
        stubJson("/v1/thing", 400, "{\"error\":\"nope\"}");

        assertThatThrownBy(() -> call("thing", type(Thing.class), EmptyKind.OPTIONAL))
                .isExactlyInstanceOf(SleeperBadRequestException.class)
                .satisfies(e -> assertThat(((SleeperApiException) e).bodySnippet()).contains("nope"));
    }

    @Test
    void status_429_throws_server_side_rate_limit() {
        stubJson("/v1/thing", 429, "slow down");

        assertThatThrownBy(() -> call("thing", type(Thing.class), EmptyKind.OPTIONAL))
                .isExactlyInstanceOf(SleeperRateLimitException.class)
                .satisfies(e -> assertThat(((SleeperRateLimitException) e).isClientSide()).isFalse());
    }

    @Test
    void status_500_and_503_throw_server_exception() {
        stubJson("/v1/a", 500, "boom");
        stubJson("/v1/b", 503, "maintenance");

        assertThatThrownBy(() -> call("a", type(Thing.class), EmptyKind.OPTIONAL))
                .isExactlyInstanceOf(SleeperServerException.class)
                .extracting(e -> ((SleeperApiException) e).status()).isEqualTo(500);
        assertThatThrownBy(() -> call("b", type(Thing.class), EmptyKind.OPTIONAL))
                .isExactlyInstanceOf(SleeperServerException.class)
                .extracting(e -> ((SleeperApiException) e).status()).isEqualTo(503);
    }

    @Test
    void error_body_snippet_is_truncated_to_512_bytes() {
        stubJson("/v1/thing", 500, "x".repeat(10_000));

        assertThatThrownBy(() -> call("thing", type(Thing.class), EmptyKind.OPTIONAL))
                .satisfies(e -> assertThat(((SleeperApiException) e).bodySnippet()).hasSize(512));
    }

    // ---- deserialisation failures ----

    @Test
    void malformed_json_throws_deserialization_exception_with_snippet() {
        stubJson("/v1/thing", 200, "{\"name\": ");

        assertThatThrownBy(() -> call("thing", type(Thing.class), EmptyKind.OPTIONAL))
                .isExactlyInstanceOf(SleeperDeserializationException.class)
                .satisfies(e -> assertThat(((SleeperDeserializationException) e).bodySnippet())
                        .startsWith("{\"name\""));
    }

    @Test
    void type_mismatch_reports_json_path() {
        stubJson("/v1/thing", 200, "[{\"name\":\"a\"},{\"total_rosters\":\"abc\"}]");

        assertThatThrownBy(() -> call("thing", listOf(Thing.class), EmptyKind.LIST))
                .isExactlyInstanceOf(SleeperDeserializationException.class)
                .satisfies(e -> assertThat(((SleeperDeserializationException) e).jsonPath())
                        .isEqualTo("$[1].total_rosters"));
    }

    // ---- transport ----

    @Test
    void connection_refused_throws_transport_exception() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        Requester r = requester(URI.create("http://localhost:" + closedPort), Duration.ofSeconds(2));

        assertThatThrownBy(() -> r.get("/t", List.of("thing"), Map.of(), type(Thing.class),
                EmptyKind.OPTIONAL, null))
                .isExactlyInstanceOf(SleeperTransportException.class)
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void request_timeout_throws_transport_exception() {
        wm.stubFor(get(urlEqualTo("/v1/slow")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}").withFixedDelay(2000)));
        Requester r = requester(URI.create(wm.baseUrl() + "/v1"), Duration.ofMillis(300));

        assertThatThrownBy(() -> r.get("/t", List.of("slow"), Map.of(), type(Thing.class),
                EmptyKind.OPTIONAL, null))
                .isExactlyInstanceOf(SleeperTransportException.class);
    }

    @Test
    void timeout_override_replaces_default_timeout() {
        wm.stubFor(get(urlEqualTo("/v1/slow")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}").withFixedDelay(1000)));
        Requester r = requester(URI.create(wm.baseUrl() + "/v1"), Duration.ofSeconds(10));

        assertThatThrownBy(() -> r.get("/t", List.of("slow"), Map.of(), type(Thing.class),
                EmptyKind.OPTIONAL, Duration.ofMillis(200)))
                .isExactlyInstanceOf(SleeperTransportException.class);
    }

    @Test
    void interrupted_thread_restores_flag_and_throws_transport_exception() {
        wm.stubFor(get(urlEqualTo("/v1/slow")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}").withFixedDelay(1000)));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> call("slow", type(Thing.class), EmptyKind.OPTIONAL))
                    .isExactlyInstanceOf(SleeperTransportException.class)
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    // ---- request shape ----

    @Test
    void path_segments_are_percent_encoded_individually() {
        stubJson("/v1/user/some%20user%2F%C3%BC", 200, "{\"name\":\"u\"}");

        Optional<Thing> result = requester().get("/t", List.of("user", "some user/ü"), Map.of(),
                type(Thing.class), EmptyKind.OPTIONAL, null);

        assertThat(result).isPresent();
        wm.verify(getRequestedFor(urlEqualTo("/v1/user/some%20user%2F%C3%BC")));
    }

    @Test
    void plus_in_a_segment_is_encoded_not_treated_as_space() {
        stubJson("/v1/user/a%2Bb", 200, "{\"name\":\"u\"}");

        Optional<Thing> result = requester().get("/t", List.of("user", "a+b"), Map.of(),
                type(Thing.class), EmptyKind.OPTIONAL, null);

        assertThat(result).isPresent();
    }

    @Test
    void query_parameters_are_encoded_in_order() {
        stubJson("/v1/players?position=QB&note=a%20b%26c", 200, "{}");
        Map<String, String> query = new LinkedHashMap<>();
        query.put("position", "QB");
        query.put("note", "a b&c");

        Map<String, Thing> result = requester().get("/t", List.of("players"), query,
                mapOf(Thing.class), EmptyKind.MAP, null);

        assertThat(result).isEmpty();
        wm.verify(getRequestedFor(urlEqualTo("/v1/players?position=QB&note=a%20b%26c")));
    }

    @Test
    void accept_and_user_agent_headers_are_sent() {
        stubJson("/v1/thing", 200, "{}");

        call("thing", type(Thing.class), EmptyKind.OPTIONAL);

        wm.verify(getRequestedFor(urlEqualTo("/v1/thing"))
                .withHeader("Accept", com.github.tomakehurst.wiremock.client.WireMock.equalTo("application/json"))
                .withHeader("User-Agent", com.github.tomakehurst.wiremock.client.WireMock.equalTo(USER_AGENT)));
    }

    @Test
    void base_uri_with_trailing_slash_does_not_produce_double_slash() {
        stubJson("/v1/thing", 200, "{}");
        Requester r = requester(URI.create(wm.baseUrl() + "/v1/"), Duration.ofSeconds(5));

        Optional<Thing> result = r.get("/t", List.of("thing"), Map.of(), type(Thing.class),
                EmptyKind.OPTIONAL, null);

        assertThat(result).isPresent();
    }

    @Test
    void non_positive_timeout_is_rejected() {
        assertThatThrownBy(() -> requester(URI.create(wm.baseUrl()), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
