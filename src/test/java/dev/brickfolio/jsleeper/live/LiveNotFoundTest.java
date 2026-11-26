package dev.brickfolio.jsleeper.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Re-runs the spec's D7 probe with raw HTTP (not the client) and asserts the not-found behaviour
 * the library's empty-result rules depend on. If Sleeper changes any row, this fails, so results
 * never silently change. Needs no league ID.
 */
@Tag("live")
class LiveNotFoundTest {

    private static final String BASE = "https://api.sleeper.app";
    private static final String NO_SUCH_ID = "999999999999999999";
    private static final String NO_SUCH_USER = "zzzz_no_such_user_xq9";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** A probe sent with the headers the library sends ({@code Accept: application/json}). */
    private static Arguments row(String path, int status, String contentTypePrefix, String exactBody) {
        return Arguments.of(path, true, status, contentTypePrefix, exactBody, null);
    }

    static Stream<Arguments> d7Table() {
        return Stream.of(
                // 200 + null
                row("/v1/user/" + NO_SUCH_USER, 200, "application/json", "null"),
                row("/v1/state/" + NO_SUCH_USER, 200, "application/json", "null"),
                // 404 + null (JSON)
                row("/v1/league/" + NO_SUCH_ID, 404, "application/json", "null"),
                row("/v1/league/" + NO_SUCH_ID + "/rosters", 404, "application/json", "null"),
                row("/v1/league/" + NO_SUCH_ID + "/matchups/1", 404, "application/json", "null"),
                row("/v1/league/" + NO_SUCH_ID + "/winners_bracket", 404, "application/json", "null"),
                row("/v1/draft/" + NO_SUCH_ID, 404, "application/json", "null"),
                row("/v1/draft/" + NO_SUCH_ID + "/picks", 404, "application/json", "null"),
                row("/v1/draft/" + NO_SUCH_ID + "/traded_picks", 404, "application/json", "null"),
                // 200 + []
                row("/v1/user/" + NO_SUCH_ID + "/leagues/nfl/2026", 200, "application/json", "[]"),
                row("/v1/user/" + NO_SUCH_ID + "/drafts/nfl/2026", 200, "application/json", "[]"),
                row("/v1/league/" + NO_SUCH_ID + "/users", 200, "application/json", "[]"),
                row("/v1/league/" + NO_SUCH_ID + "/transactions/1", 200, "application/json", "[]"),
                row("/v1/league/" + NO_SUCH_ID + "/traded_picks", 200, "application/json", "[]"),
                row("/v1/league/" + NO_SUCH_ID + "/drafts", 200, "application/json", "[]"),
                row("/v1/players/nfl/trending/" + NO_SUCH_USER, 200, "application/json", "[]"),
                // An unknown route, probed as in the spec's D7 (no Accept header): an HTML page.
                Arguments.of("/bogus/path", false, 404, "text/html", null, "<"),
                // The same route with the library's Accept header: still a 404 and still JSON, but the
                // body is an error object, not the "null" of a missing resource. A client that treats
                // every JSON 404 as "no data" would hide a mistyped path, so it must look at the body.
                Arguments.of("/v1/bogus/path", true, 404, "application/json", null, "{\"errors\""));
    }

    @ParameterizedTest(name = "{0} (accept json: {1})")
    @MethodSource("d7Table")
    void sleeper_still_answers_missing_ids_as_the_spec_records(
            String path, boolean sendAccept, int status, String contentTypePrefix, String exactBody,
            String bodyStartsWith) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "jsleeper-live-test (+https://github.com/itshanney/jsleeper)")
                .GET();
        if (sendAccept) {
            builder.header("Accept", "application/json");
        }
        HttpRequest request = builder.build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).as("status of %s", path).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .as("content type of %s", path).startsWith(contentTypePrefix);
        if (exactBody != null) {
            assertThat(response.body().strip()).as("body of %s", path).isEqualTo(exactBody);
        } else {
            assertThat(response.body().strip()).as("body of %s", path).startsWith(bodyStartsWith);
        }
    }
}
