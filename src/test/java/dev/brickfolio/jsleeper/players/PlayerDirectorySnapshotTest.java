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
import dev.brickfolio.jsleeper.model.Player;
import dev.brickfolio.jsleeper.model.Sport;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

class PlayerDirectorySnapshotTest {

    private static final String URL = "/v1/players/nfl";
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

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

    private static final class CloseRecordingOutput extends ByteArrayOutputStream {
        boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final class CloseRecordingInput extends ByteArrayInputStream {
        boolean closed;

        CloseRecordingInput(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private final TestClock clock = new TestClock();
    private final SleeperClient client = SleeperClient.builder().baseUri(wm.baseUrl() + "/v1")
            .clock(clock).retryPolicy(RetryPolicy.none()).rateLimit(RateLimit.disabled()).build();

    private void stubSample() {
        wm.stubFor(get(urlEqualTo(URL)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(Fixtures.bytes("players_nfl_sample.json"))));
    }

    private int requests() {
        return wm.findAll(getRequestedFor(urlEqualTo(URL))).size();
    }

    private PlayerDirectory directory() {
        return PlayerDirectory.builder(client).build();
    }

    private byte[] snapshotOfLoadedSample() {
        stubSample();
        PlayerDirectory source = directory();
        source.refresh();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        source.writeSnapshot(out);
        wm.resetRequests();
        return out.toByteArray();
    }

    private static InputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void round_trip_gives_players_equal_to_the_originals_including_external_ids_and_full_names() {
        stubSample();
        PlayerDirectory source = directory();
        source.refresh();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        source.writeSnapshot(out);

        PlayerDirectory copy = directory();
        copy.loadSnapshot(new ByteArrayInputStream(out.toByteArray()));

        assertThat(copy.all()).hasSize(16).isEqualTo(source.all());
        assertThat(copy.all().keySet()).containsExactlyElementsOf(source.all().keySet());
        Player rodgers = copy.get("96").orElseThrow();
        assertThat(rodgers.externalIds().statsId()).isEqualTo("213957");
        assertThat(rodgers.externalIds().gsisId()).isEqualTo("00-0023459");
        assertThat(copy.get("KC").orElseThrow().fullName()).isEqualTo("Kansas City Chiefs");
        assertThat(copy.get("96").orElseThrow().team()).isNull();
    }

    @Test
    void the_written_envelope_has_format_sport_loaded_at_and_flat_wire_shaped_players() throws Exception {
        stubSample();
        PlayerDirectory source = directory();
        source.refresh();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        source.writeSnapshot(out);

        var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(out.toByteArray());
        assertThat(root.get("format").asInt()).isEqualTo(1);
        assertThat(root.get("sport").asText()).isEqualTo("nfl");
        assertThat(Instant.parse(root.get("loadedAt").asText())).isEqualTo(START);
        var rodgers = root.get("players").get("96");
        assertThat(rodgers.get("player_id").asText()).isEqualTo("96");
        assertThat(rodgers.has("external_ids")).isFalse();
        assertThat(rodgers.get("stats_id").asText()).isEqualTo("213957");
    }

    @Test
    void a_fresh_snapshot_serves_lookups_without_any_http_request() {
        byte[] snapshot = snapshotOfLoadedSample();
        PlayerDirectory dir = directory();

        dir.loadSnapshot(new ByteArrayInputStream(snapshot));
        clock.advance(Duration.ofHours(23));

        assertThat(dir.get("KC")).isPresent();
        assertThat(dir.loadedAt()).contains(START);
        assertThat(requests()).isZero();
    }

    @Test
    void an_expired_snapshot_is_refreshed_by_the_next_lookup() {
        byte[] snapshot = snapshotOfLoadedSample();
        PlayerDirectory dir = directory();
        dir.loadSnapshot(new ByteArrayInputStream(snapshot));
        clock.advance(Duration.ofHours(25));

        assertThat(dir.get("KC")).isPresent();

        assertThat(requests()).isEqualTo(1);
        assertThat(dir.loadedAt()).contains(START.plus(Duration.ofHours(25)));
    }

    @Test
    void loading_a_snapshot_replaces_existing_data() {
        byte[] snapshot = snapshotOfLoadedSample();
        wm.stubFor(get(urlEqualTo(URL)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"X\":{\"player_id\":\"X\"}}")));
        PlayerDirectory dir = directory();
        dir.get("X");

        dir.loadSnapshot(new ByteArrayInputStream(snapshot));

        assertThat(dir.get("X")).isEmpty();
        assertThat(dir.get("KC")).isPresent();
    }

    @Test
    void a_sport_mismatch_is_rejected_and_existing_data_is_kept() {
        stubSample();
        PlayerDirectory dir = directory();
        dir.get("KC");

        assertThatThrownBy(() -> dir.loadSnapshot(stream(
                "{\"format\":1,\"sport\":\"nba\",\"loadedAt\":\"2026-01-01T00:00:00Z\",\"players\":{}}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nba");
        assertThat(dir.get("KC")).isPresent();
    }

    @Test
    void unsupported_or_missing_format_is_rejected() {
        PlayerDirectory dir = directory();

        assertThatThrownBy(() -> dir.loadSnapshot(stream(
                "{\"format\":2,\"sport\":\"nfl\",\"loadedAt\":\"2026-01-01T00:00:00Z\",\"players\":{}}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("format");
        assertThatThrownBy(() -> dir.loadSnapshot(stream(
                "{\"sport\":\"nfl\",\"loadedAt\":\"2026-01-01T00:00:00Z\",\"players\":{}}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformed_json_is_rejected_with_the_parse_failure_as_cause() {
        PlayerDirectory dir = directory();

        assertThatThrownBy(() -> dir.loadSnapshot(stream("{not json")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasCauseInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThatThrownBy(() -> dir.loadSnapshot(stream("{\"format\":1,\"sport\":\"nfl\",")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dir.loadSnapshot(stream("")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dir.loadSnapshot(stream("[1,2]")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_bad_loaded_at_or_players_section_is_rejected() {
        PlayerDirectory dir = directory();

        assertThatThrownBy(() -> dir.loadSnapshot(stream(
                "{\"format\":1,\"sport\":\"nfl\",\"loadedAt\":\"yesterday\",\"players\":{}}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("loadedAt");
        assertThatThrownBy(() -> dir.loadSnapshot(stream(
                "{\"format\":1,\"sport\":\"nfl\",\"loadedAt\":\"2026-01-01T00:00:00Z\",\"players\":[]}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("players");
        assertThatThrownBy(() -> dir.loadSnapshot(stream(
                "{\"format\":1,\"sport\":\"nfl\",\"loadedAt\":\"2026-01-01T00:00:00Z\","
                        + "\"players\":{\"1\":{\"player_id\":\"1\",\"age\":\"old\"}}}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("player 1");
    }

    @Test
    void a_read_failure_is_an_unchecked_io_exception() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("disk gone");
            }
        };

        assertThatThrownBy(() -> directory().loadSnapshot(broken)).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void writing_with_no_data_throws_illegal_state() {
        assertThatThrownBy(() -> directory().writeSnapshot(new ByteArrayOutputStream()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_write_failure_is_an_unchecked_io_exception() {
        stubSample();
        PlayerDirectory dir = directory();
        dir.refresh();
        OutputStream broken = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("disk full");
            }
        };

        assertThatThrownBy(() -> dir.writeSnapshot(broken)).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void neither_stream_is_closed_and_the_output_is_flushed_and_complete() throws Exception {
        stubSample();
        PlayerDirectory dir = directory();
        dir.refresh();
        CloseRecordingOutput out = new CloseRecordingOutput();

        dir.writeSnapshot(out);

        assertThat(out.closed).isFalse();
        CloseRecordingInput in = new CloseRecordingInput(out.toByteArray());
        directory().loadSnapshot(in);
        assertThat(in.closed).isFalse();
        assertThat(out.toString(StandardCharsets.UTF_8)).endsWith("}");
    }

    @Test
    void usage_example_persisting_the_player_snapshot_across_restarts(@TempDir Path tempDir) throws Exception {
        stubSample();
        Path snapshot = tempDir.resolve("players-nfl.json");
        SleeperClient sleeper = client;

        // First run: no file yet, so the lookup loads from Sleeper and the snapshot is saved.
        PlayerDirectory firstRun = PlayerDirectory.builder(sleeper).build();
        runUsageExample11(firstRun, snapshot);
        assertThat(requests()).isEqualTo(1);
        assertThat(snapshot).exists();

        // Restart within 24 hours: loadSnapshot supplies the data, so no further HTTP call.
        clock.advance(Duration.ofHours(2));
        PlayerDirectory secondRun = PlayerDirectory.builder(sleeper).build();
        String line = runUsageExample11(secondRun, snapshot);

        assertThat(requests()).isEqualTo(1);
        assertThat(line).startsWith("Aaron Rodgers").contains("null");
    }

    /** Usage Examples section 11; "96" stands in for 4046, which is not in the recorded sample. */
    private String runUsageExample11(PlayerDirectory players, Path snapshot) throws IOException {
        if (Files.exists(snapshot)) {
            try (InputStream in = Files.newInputStream(snapshot)) {
                players.loadSnapshot(in);        // keeps the stored loadedAt; no HTTP if < 24h old
            }
        }

        Player mahomes = players.get("96").orElseThrow();   // refreshes only if the TTL has expired
        String line = mahomes.fullName() + " " + mahomes.team() + " " + mahomes.externalIds().espnId();

        try (OutputStream out = Files.newOutputStream(snapshot)) {
            players.writeSnapshot(out);
        }
        return line;
    }

    @Test
    void sport_is_part_of_the_snapshot_so_an_nba_directory_accepts_only_nba_snapshots() {
        wm.stubFor(get(urlEqualTo("/v1/players/nba")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"1\":{\"player_id\":\"1\"}}")));
        PlayerDirectory nba = PlayerDirectory.builder(client).sport(Sport.NBA).build();
        nba.refresh();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        nba.writeSnapshot(out);

        PlayerDirectory otherNba = PlayerDirectory.builder(client).sport(Sport.NBA).build();
        otherNba.loadSnapshot(new ByteArrayInputStream(out.toByteArray()));

        assertThat(otherNba.get("1")).isPresent();
        assertThatThrownBy(() -> directory().loadSnapshot(new ByteArrayInputStream(out.toByteArray())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
