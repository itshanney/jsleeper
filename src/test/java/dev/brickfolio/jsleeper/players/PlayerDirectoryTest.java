package dev.brickfolio.jsleeper.players;

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
import dev.brickfolio.jsleeper.RetryPolicy;
import dev.brickfolio.jsleeper.SleeperClient;
import dev.brickfolio.jsleeper.error.SleeperServerException;
import dev.brickfolio.jsleeper.model.Player;
import dev.brickfolio.jsleeper.model.Sport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class PlayerDirectoryTest {

    private static final String URL = "/v1/players/nfl";
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration DAY = Duration.ofHours(24);

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    private static final class TestClock extends Clock {
        private final AtomicLong millis = new AtomicLong(START.toEpochMilli());

        void advance(Duration d) {
            millis.addAndGet(d.toMillis());
        }

        @Override
        public long millis() {
            return millis.get();
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private final TestClock clock = new TestClock();
    private final SleeperClient client = SleeperClient.builder().baseUri(wm.baseUrl() + "/v1")
            .clock(clock).retryPolicy(RetryPolicy.none()).rateLimit(RateLimit.disabled()).build();

    private void stubSample() {
        wm.stubFor(get(urlEqualTo(URL)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(Fixtures.bytes("players_nfl_sample.json"))));
    }

    private void stubBody(String body, int delayMillis) {
        wm.stubFor(get(urlEqualTo(URL)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body).withFixedDelay(delayMillis)));
    }

    private void stubFailure() {
        wm.stubFor(get(urlEqualTo(URL)).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json").withBody("boom")));
    }

    private PlayerDirectory directory() {
        return PlayerDirectory.builder(client).build();
    }

    private int requests() {
        return wm.findAll(getRequestedFor(urlEqualTo(URL))).size();
    }

    // ---- lazy loading and TTL ----

    @Test
    void building_makes_no_http_call_and_nothing_is_loaded() {
        stubSample();

        PlayerDirectory dir = directory();

        assertThat(requests()).isZero();
        assertThat(dir.loadedAt()).isEmpty();
    }

    @Test
    void the_first_lookup_loads_the_players_and_records_the_load_time() {
        stubSample();
        PlayerDirectory dir = directory();

        Optional<Player> kc = dir.get("KC");

        assertThat(kc).isPresent();
        assertThat(kc.get().fullName()).isEqualTo("Kansas City Chiefs");
        assertThat(requests()).isEqualTo(1);
        assertThat(dir.loadedAt()).contains(START);
    }

    @Test
    void lookups_within_the_ttl_make_no_further_requests() {
        stubSample();
        PlayerDirectory dir = directory();
        dir.get("KC");

        clock.advance(DAY.minusMillis(1));
        dir.get("KC");
        dir.all();

        assertThat(requests()).isEqualTo(1);
    }

    @Test
    void the_first_lookup_after_the_ttl_triggers_exactly_one_refresh() {
        stubSample();
        PlayerDirectory dir = directory();
        dir.get("KC");

        clock.advance(DAY);
        dir.get("KC");
        dir.get("96");
        dir.all();

        assertThat(requests()).isEqualTo(2);
        assertThat(dir.loadedAt()).contains(START.plus(DAY));
    }

    @Test
    void an_unknown_id_is_empty_and_does_not_reload() {
        stubSample();
        PlayerDirectory dir = directory();

        assertThat(dir.get("no-such-player")).isEmpty();
        assertThat(dir.get("no-such-player")).isEmpty();

        assertThat(requests()).isEqualTo(1);
    }

    @Test
    void all_returns_every_player_as_an_unmodifiable_map() {
        stubSample();
        PlayerDirectory dir = directory();

        Map<String, Player> all = dir.all();

        assertThat(all).hasSize(16).containsKeys("KC", "96");
        assertThatThrownBy(() -> all.put("x", null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void the_custom_ttl_controls_when_a_refresh_happens() {
        stubSample();
        PlayerDirectory dir = PlayerDirectory.builder(client).ttl(Duration.ofMinutes(90)).build();
        dir.get("KC");

        clock.advance(Duration.ofMinutes(89));
        dir.get("KC");
        assertThat(requests()).isEqualTo(1);
        clock.advance(Duration.ofMinutes(1));
        dir.get("KC");
        assertThat(requests()).isEqualTo(2);
    }

    // ---- concurrency ----

    @Test
    void fifty_threads_hitting_a_cold_directory_cause_exactly_one_request() throws Exception {
        stubBody(new String(Fixtures.bytes("players_nfl_sample.json"), java.nio.charset.StandardCharsets.UTF_8), 300);
        PlayerDirectory dir = directory();
        int threads = 50;
        CountDownLatch go = new CountDownLatch(1);

        List<Future<Optional<Player>>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return dir.get("KC");
                }));
            }
            go.countDown();
            for (Future<Optional<Player>> f : results) {
                assertThat(f.get()).isPresent();
            }
        }

        assertThat(requests()).isEqualTo(1);
    }

    @Test
    void while_one_thread_refreshes_other_threads_return_stale_data_without_waiting() throws Exception {
        stubSample();
        PlayerDirectory dir = directory();
        dir.get("KC");
        clock.advance(DAY);
        stubBody("{\"NEW\":{\"player_id\":\"NEW\",\"first_name\":\"New\",\"last_name\":\"Guy\"}}", 800);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Optional<Player>> refresher = pool.submit(() -> dir.get("NEW"));
            Thread.sleep(150);  // let the refresher take the lock and block in the slow HTTP call

            long startNanos = System.nanoTime();
            List<Future<Optional<Player>>> others = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                others.add(pool.submit(() -> dir.get("KC")));
            }
            for (Future<Optional<Player>> f : others) {
                assertThat(f.get()).as("stale data is served").isPresent();
            }
            Duration waited = Duration.ofNanos(System.nanoTime() - startNanos);

            assertThat(waited).as("readers do not wait for the 800ms refresh").isLessThan(Duration.ofMillis(500));
            assertThat(refresher.get()).as("the refresher sees the new data").isPresent();
        }

        assertThat(requests()).isEqualTo(2);
        assertThat(dir.get("KC")).as("after the refresh the old data is gone").isEmpty();
        assertThat(dir.get("NEW")).isPresent();
    }

    // ---- failures ----

    @Test
    void a_failed_refresh_keeps_old_data_and_backs_off_by_the_ttl_capped_at_one_hour() {
        stubSample();
        PlayerDirectory dir = directory();  // 24h TTL, so the back-off is one hour
        dir.get("KC");
        clock.advance(DAY);
        stubFailure();

        assertThat(dir.get("KC")).as("stale data is served on failure").isPresent();
        assertThat(requests()).isEqualTo(2);

        assertThat(dir.get("KC")).isPresent();
        clock.advance(Duration.ofHours(1).minusMillis(1));
        assertThat(dir.get("KC")).isPresent();
        assertThat(requests()).as("no retry during the back-off").isEqualTo(2);

        clock.advance(Duration.ofMillis(1));
        dir.get("KC");
        assertThat(requests()).as("retry once the back-off has passed").isEqualTo(3);
        assertThat(dir.loadedAt()).as("the data was never replaced").contains(START);
    }

    @Test
    void the_back_off_is_the_ttl_when_that_is_shorter_than_an_hour() {
        stubSample();
        PlayerDirectory dir = PlayerDirectory.builder(client).ttl(Duration.ofMinutes(10)).build();
        dir.get("KC");
        clock.advance(Duration.ofMinutes(10));
        stubFailure();
        dir.get("KC");
        assertThat(requests()).isEqualTo(2);

        clock.advance(Duration.ofMinutes(10).minusMillis(1));
        dir.get("KC");
        assertThat(requests()).isEqualTo(2);
        clock.advance(Duration.ofMillis(1));
        dir.get("KC");
        assertThat(requests()).isEqualTo(3);
    }

    @Test
    void a_recovered_refresh_after_a_failure_replaces_the_data() {
        stubSample();
        PlayerDirectory dir = directory();
        dir.get("KC");
        clock.advance(DAY);
        stubFailure();
        dir.get("KC");
        clock.advance(Duration.ofHours(1));
        stubBody("{\"NEW\":{\"player_id\":\"NEW\"}}", 0);

        assertThat(dir.get("NEW")).isPresent();
        assertThat(dir.get("KC")).isEmpty();
    }

    @Test
    void failure_with_no_data_is_rethrown_and_the_next_call_retries() {
        stubFailure();
        PlayerDirectory dir = directory();

        assertThatThrownBy(() -> dir.get("KC")).isExactlyInstanceOf(SleeperServerException.class);
        assertThat(dir.loadedAt()).isEmpty();

        stubSample();
        assertThat(dir.get("KC")).isPresent();
        assertThat(requests()).isEqualTo(2);
    }

    // ---- forced refresh ----

    @Test
    void forced_refresh_fetches_again_regardless_of_the_ttl() {
        stubSample();
        PlayerDirectory dir = directory();
        dir.get("KC");
        clock.advance(Duration.ofMinutes(5));

        dir.refresh();

        assertThat(requests()).isEqualTo(2);
        assertThat(dir.loadedAt()).contains(START.plus(Duration.ofMinutes(5)));
    }

    @Test
    void forced_refresh_works_on_a_cold_directory() {
        stubSample();
        PlayerDirectory dir = directory();

        dir.refresh();

        assertThat(dir.loadedAt()).contains(START);
        assertThat(dir.get("KC")).isPresent();
        assertThat(requests()).isEqualTo(1);
    }

    @Test
    void a_failed_forced_refresh_is_thrown_but_keeps_the_old_data() {
        stubSample();
        PlayerDirectory dir = directory();
        dir.get("KC");
        stubFailure();

        assertThatThrownBy(dir::refresh).isExactlyInstanceOf(SleeperServerException.class);

        assertThat(dir.get("KC")).isPresent();
        assertThat(dir.loadedAt()).contains(START);
    }

    // ---- builder ----

    @Test
    void builder_rejects_bad_values() {
        assertThatThrownBy(() -> PlayerDirectory.builder(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PlayerDirectory.builder(client).ttl(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlayerDirectory.builder(client).ttl(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlayerDirectory.builder(client).sport(Sport.UNKNOWN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlayerDirectory.builder(client).sport(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> directory().get(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void the_sport_selects_the_endpoint() {
        wm.stubFor(get(urlEqualTo("/v1/players/nba")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"1\":{\"player_id\":\"1\"}}")));

        PlayerDirectory dir = PlayerDirectory.builder(client).sport(Sport.NBA).build();

        assertThat(dir.get("1")).isPresent();
        wm.verify(1, getRequestedFor(urlEqualTo("/v1/players/nba")));
    }

    @Test
    void usage_example_lookup_by_id_with_a_team_defense_fallback_compiles() {
        stubSample();
        SleeperClient sleeper = client;

        // Usage Examples sections 4 and 8 (the lookup part).
        PlayerDirectory players = PlayerDirectory.builder(sleeper)
                .sport(Sport.NFL)
                .ttl(Duration.ofHours(24))
                .build();                                   // lazy: no HTTP call yet

        String known = players.get("KC").map(Player::fullName).orElse("KC");
        String unknown = players.get("ZZZ").map(Player::fullName).orElse("ZZZ");

        assertThat(known).isEqualTo("Kansas City Chiefs");
        assertThat(unknown).isEqualTo("ZZZ");
    }
}
