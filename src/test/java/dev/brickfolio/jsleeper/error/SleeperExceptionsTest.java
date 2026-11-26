package dev.brickfolio.jsleeper.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SleeperExceptionsTest {

    private static final String METHOD = "GET";
    private static final String PATH = "/league/1";

    @Test
    void status_400_maps_to_bad_request() {
        var e = SleeperApiException.forStatus(METHOD, PATH, 400, "bad");

        assertThat(e).isExactlyInstanceOf(SleeperBadRequestException.class);
        assertThat(e.status()).isEqualTo(400);
    }

    @Test
    void status_429_maps_to_server_side_rate_limit() {
        var e = SleeperApiException.forStatus(METHOD, PATH, 429, null);

        assertThat(e).isExactlyInstanceOf(SleeperRateLimitException.class);
        assertThat(e.status()).isEqualTo(429);
        assertThat(((SleeperRateLimitException) e).isClientSide()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 599})
    void server_statuses_map_to_server_exception(int status) {
        var e = SleeperApiException.forStatus(METHOD, PATH, status, "oops");

        assertThat(e).isExactlyInstanceOf(SleeperServerException.class);
        assertThat(e.status()).isEqualTo(status);
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 401, 404, 418, 499, 600})
    void other_statuses_map_to_base_api_exception(int status) {
        var e = SleeperApiException.forStatus(METHOD, PATH, status, "x");

        assertThat(e).isExactlyInstanceOf(SleeperApiException.class);
        assertThat(e.status()).isEqualTo(status);
    }

    @Test
    void api_exception_exposes_method_path_status_and_body() {
        var e = SleeperApiException.forStatus("GET", "/draft/9", 503, "down for maintenance");

        assertThat(e.method()).isEqualTo("GET");
        assertThat(e.path()).isEqualTo("/draft/9");
        assertThat(e.bodySnippet()).isEqualTo("down for maintenance");
        assertThat(e.getMessage()).contains("GET", "/draft/9", "503", "down for maintenance");
    }

    @Test
    void null_body_gives_null_snippet() {
        var e = SleeperApiException.forStatus(METHOD, PATH, 500, null);

        assertThat(e.bodySnippet()).isNull();
        assertThat(e.getMessage()).doesNotContain("null");
    }

    @Test
    void ascii_body_over_limit_is_truncated_to_512_bytes() {
        var e = SleeperApiException.forStatus(METHOD, PATH, 500, "a".repeat(1000));

        assertThat(e.bodySnippet()).isEqualTo("a".repeat(512));
    }

    @Test
    void body_of_exactly_512_bytes_is_kept_whole() {
        var e = SleeperApiException.forStatus(METHOD, PATH, 500, "a".repeat(512));

        assertThat(e.bodySnippet()).hasSize(512);
    }

    @Test
    void multibyte_character_straddling_the_limit_is_dropped_not_split() {
        // 511 ASCII bytes then a 2-byte character: the character starts at byte 511 and ends at 512.
        String body = "a".repeat(511) + "ü" + "tail";

        var e = SleeperApiException.forStatus(METHOD, PATH, 500, body);

        assertThat(e.bodySnippet()).isEqualTo("a".repeat(511));
        assertThat(e.bodySnippet()).doesNotContain("�");
    }

    @Test
    void multibyte_character_ending_exactly_at_the_limit_is_kept() {
        String body = "a".repeat(510) + "ü" + "tail";

        var e = SleeperApiException.forStatus(METHOD, PATH, 500, body);

        assertThat(e.bodySnippet()).isEqualTo("a".repeat(510) + "ü");
        assertThat(e.bodySnippet().getBytes(StandardCharsets.UTF_8)).hasSize(512);
    }

    @Test
    void four_byte_character_straddling_the_limit_is_dropped_whole() {
        String emoji = new String(Character.toChars(0x1F600));
        String body = "a".repeat(510) + emoji + emoji;

        var e = SleeperApiException.forStatus(METHOD, PATH, 500, body);

        assertThat(e.bodySnippet()).isEqualTo("a".repeat(510));
    }

    @Test
    void client_side_rate_limit_has_status_429_flag_and_no_body() {
        var e = SleeperRateLimitException.clientSide(METHOD, PATH);

        assertThat(e.isClientSide()).isTrue();
        assertThat(e.status()).isEqualTo(429);
        assertThat(e.bodySnippet()).isNull();
        assertThat(e.method()).isEqualTo(METHOD);
        assertThat(e.path()).isEqualTo(PATH);
        assertThat(e).isInstanceOf(SleeperApiException.class);
    }

    @Test
    void transport_exception_preserves_cause_method_and_path() {
        var cause = new IOException("connection refused");

        var e = new SleeperTransportException(METHOD, PATH, cause);

        assertThat(e.getCause()).isSameAs(cause);
        assertThat(e.method()).isEqualTo(METHOD);
        assertThat(e.path()).isEqualTo(PATH);
        assertThat(e).isInstanceOf(SleeperException.class).isNotInstanceOf(SleeperApiException.class);
    }

    @Test
    void deserialization_exception_exposes_json_path_truncated_snippet_and_cause() {
        var cause = new IllegalStateException("bad token");

        var e = new SleeperDeserializationException(METHOD, PATH, "$[3].roster_id",
                "{".repeat(600), cause);

        assertThat(e.jsonPath()).isEqualTo("$[3].roster_id");
        assertThat(e.bodySnippet()).isEqualTo("{".repeat(512));
        assertThat(e.getCause()).isSameAs(cause);
        assertThat(e.method()).isEqualTo(METHOD);
        assertThat(e.path()).isEqualTo(PATH);
        assertThat(e.getMessage()).contains("$[3].roster_id");
    }

    @Test
    void all_exceptions_are_unchecked_sleeper_exceptions() {
        assertThat(new SleeperBadRequestException(METHOD, PATH, null))
                .isInstanceOf(RuntimeException.class).isInstanceOf(SleeperApiException.class);
        assertThat(new SleeperServerException(METHOD, PATH, 500, null))
                .isInstanceOf(SleeperApiException.class);
        assertThat(new SleeperDeserializationException(METHOD, PATH, null, null, null))
                .isInstanceOf(SleeperException.class);
    }
}
