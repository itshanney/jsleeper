package dev.brickfolio.jsleeper;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.brickfolio.jsleeper.error.SleeperApiException;
import dev.brickfolio.jsleeper.model.SeasonType;
import dev.brickfolio.jsleeper.model.Sport;
import dev.brickfolio.jsleeper.model.SportState;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class SleeperClientTest {

    private static final String LIVE_STATE_NFL = "{\"week\":12,\"season_type\":\"regular\","
            + "\"season\":\"2026\",\"leg\":12,\"league_season\":\"2026\",\"previous_season\":\"2025\","
            + "\"season_start_date\":\"2026-09-09\",\"display_week\":12,"
            + "\"league_create_season\":\"2026\",\"season_has_scores\":true}";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    private SleeperClientBuilder builder() {
        return SleeperClient.builder().baseUri(wm.baseUrl() + "/v1");
    }

    private void stubState(String sport, int status, String body) {
        wm.stubFor(get(urlEqualTo("/v1/state/" + sport)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    // ---- state() ----

    @Test
    void state_round_trips_the_live_nfl_response() {
        stubState("nfl", 200, LIVE_STATE_NFL);

        SportState s = builder().build().state().get(Sport.NFL);

        assertThat(s.week()).isEqualTo(12);
        assertThat(s.leg()).isEqualTo(12);
        assertThat(s.displayWeek()).isEqualTo(12);
        assertThat(s.season()).isEqualTo("2026");
        assertThat(s.previousSeason()).isEqualTo("2025");
        assertThat(s.leagueSeason()).isEqualTo("2026");
        assertThat(s.leagueCreateSeason()).isEqualTo("2026");
        assertThat(s.seasonType()).isEqualTo(SeasonType.REGULAR);
        assertThat(s.seasonTypeRaw()).isEqualTo("regular");
        assertThat(s.seasonStartDate()).isEqualTo(LocalDate.of(2026, 9, 9));
        assertThat(s.seasonHasScores()).isTrue();
    }

    @Test
    void state_with_unknown_season_type_maps_to_unknown_and_keeps_raw_value() {
        stubState("nfl", 200, "{\"season_type\":\"weird\",\"week\":1}");

        SportState s = builder().build().state().get(Sport.NFL);

        assertThat(s.seasonType()).isEqualTo(SeasonType.UNKNOWN);
        assertThat(s.seasonTypeRaw()).isEqualTo("weird");
    }

    @Test
    void state_tolerates_missing_and_extra_fields() {
        stubState("nfl", 200, "{\"week\":3,\"brand_new\":[1,2]}");

        SportState s = builder().build().state().get(Sport.NFL);

        assertThat(s.week()).isEqualTo(3);
        assertThat(s.seasonType()).isEqualTo(SeasonType.UNKNOWN);
        assertThat(s.seasonTypeRaw()).isNull();
        assertThat(s.seasonStartDate()).isNull();
        assertThat(s.seasonHasScores()).isNull();
    }

    @Test
    void state_with_null_body_throws_api_exception() {
        stubState("nfl", 200, "null");

        assertThatThrownBy(() -> builder().build().state().get(Sport.NFL))
                .isExactlyInstanceOf(SleeperApiException.class);
    }

    @Test
    void state_uses_the_sport_wire_value_in_the_path() {
        stubState("nba", 200, LIVE_STATE_NFL);

        builder().build().state().get(Sport.NBA);

        wm.verify(getRequestedFor(urlEqualTo("/v1/state/nba")));
    }

    @Test
    void state_with_unknown_sport_is_rejected_before_any_request() {
        SleeperClient client = builder().build();

        assertThatThrownBy(() -> client.state().get(Sport.UNKNOWN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void state_with_null_sport_throws_npe() {
        assertThatThrownBy(() -> builder().build().state().get(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void accept_and_custom_user_agent_headers_are_sent() {
        stubState("nfl", 200, LIVE_STATE_NFL);

        builder().userAgent("my-app/2.0").build().state().get(Sport.NFL);

        wm.verify(getRequestedFor(urlEqualTo("/v1/state/nfl"))
                .withHeader("Accept", equalTo("application/json"))
                .withHeader("User-Agent", equalTo("my-app/2.0")));
    }

    @Test
    void default_user_agent_names_the_library_and_version() {
        stubState("nfl", 200, LIVE_STATE_NFL);

        builder().build().state().get(Sport.NFL);

        wm.verify(getRequestedFor(urlEqualTo("/v1/state/nfl"))
                .withHeader("User-Agent", com.github.tomakehurst.wiremock.client.WireMock.matching("jsleeper/\\S+ \\(\\+https://github\\.com/itshanney/jsleeper\\)")));
    }

    @Test
    void supplied_http_client_and_mapper_are_used_without_mutating_the_mapper() {
        stubState("nfl", 200, LIVE_STATE_NFL);
        ObjectMapper mapper = new ObjectMapper();

        SportState s = builder().httpClient(HttpClient.newHttpClient()).objectMapper(mapper)
                .requestTimeout(Duration.ofSeconds(3)).build().state().get(Sport.NFL);

        assertThat(s.week()).isEqualTo(12);
        assertThat(mapper.getPropertyNamingStrategy()).isNull();
    }

    // ---- create() and build() ----

    @Test
    void create_builds_a_client_without_any_network_call() {
        SleeperClient client = SleeperClient.create();

        assertThat(client.state()).isNotNull();
        assertThat(client.clock()).isNotNull();
    }

    @Test
    void builder_clock_is_kept() {
        Clock fixed = Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC);

        assertThat(builder().clock(fixed).build().clock()).isSameAs(fixed);
    }

    @Test
    void build_accepts_uri_typed_base_uri() {
        assertThat(SleeperClient.builder().baseUri(URI.create("http://localhost:1/v1")).build())
                .isNotNull();
    }

    @Test
    void build_rejects_blank_base_uri() {
        assertThatThrownBy(() -> SleeperClient.builder().baseUri("  ").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void build_rejects_relative_base_uri() {
        assertThatThrownBy(() -> SleeperClient.builder().baseUri("/v1").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void build_rejects_non_http_scheme() {
        assertThatThrownBy(() -> SleeperClient.builder().baseUri("ftp://example.com/v1").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void build_rejects_syntactically_invalid_base_uri() {
        assertThatThrownBy(() -> SleeperClient.builder().baseUri("http://exa mple.com").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void build_rejects_base_uri_without_host() {
        assertThatThrownBy(() -> SleeperClient.builder().baseUri("http:///v1").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void build_rejects_zero_and_negative_request_timeout() {
        assertThatThrownBy(() -> SleeperClient.builder().requestTimeout(Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SleeperClient.builder().requestTimeout(Duration.ofSeconds(-1)).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void build_rejects_blank_user_agent() {
        assertThatThrownBy(() -> SleeperClient.builder().userAgent(" ").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void client_listener_receives_events_for_a_call() {
        stubState("nfl", 200, LIVE_STATE_NFL);
        java.util.List<String> templates = new java.util.ArrayList<>();

        builder().listener(new RequestListener() {
            @Override
            public void onRequest(RequestEvent event) {
                templates.add(event.pathTemplate());
            }
        }).build().state().get(Sport.NFL);

        assertThat(templates).containsExactly("/state/{sport}");
    }

    @Test
    void null_setters_throw_npe() {
        SleeperClientBuilder b = SleeperClient.builder();

        assertThatThrownBy(() -> b.baseUri((String) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> b.baseUri((URI) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> b.httpClient(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> b.requestTimeout(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> b.userAgent(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> b.objectMapper(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> b.clock(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> b.listener(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> b.retryPolicy(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> b.rateLimit(null)).isInstanceOf(NullPointerException.class);
    }
}
