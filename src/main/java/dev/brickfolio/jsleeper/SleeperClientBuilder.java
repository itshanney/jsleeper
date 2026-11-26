package dev.brickfolio.jsleeper;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.brickfolio.jsleeper.internal.JsonCodec;
import dev.brickfolio.jsleeper.internal.RateLimiter;
import dev.brickfolio.jsleeper.internal.Requester;
import dev.brickfolio.jsleeper.internal.Sleeper;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/** Configures a {@link SleeperClient}. Not thread-safe; use from one thread, then call {@link #build()}. */
public final class SleeperClientBuilder {

    private static final String DEFAULT_BASE_URI = "https://api.sleeper.app/v1";
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String UNKNOWN_VERSION = "dev";
    private static final String REPO_URL = "https://github.com/itshanney/jsleeper";

    private String baseUri = DEFAULT_BASE_URI;
    private HttpClient httpClient;
    private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
    private String userAgent = "jsleeper/" + version() + " (+" + REPO_URL + ")";
    private ObjectMapper objectMapper;
    private Clock clock = Clock.systemUTC();
    private RetryPolicy retryPolicy = RetryPolicy.defaults();
    private RateLimit rateLimit = RateLimit.defaults();

    SleeperClientBuilder() {}

    /** Sets the API root, for example to point at a test server. Must be absolute http or https. */
    public SleeperClientBuilder baseUri(String baseUri) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
        return this;
    }

    /** Sets the API root. Must be absolute http or https. */
    public SleeperClientBuilder baseUri(URI baseUri) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri").toString();
        return this;
    }

    /** Supplies the HTTP client. Default: HTTP/2, redirects NORMAL, 5s connect timeout. */
    public SleeperClientBuilder httpClient(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        return this;
    }

    /** Sets the per-request timeout. Default 10s; must be positive. */
    public SleeperClientBuilder requestTimeout(Duration requestTimeout) {
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        return this;
    }

    /** Sets the {@code User-Agent} header. Default {@code jsleeper/<version> (+<repo url>)}; must not be blank. */
    public SleeperClientBuilder userAgent(String userAgent) {
        this.userAgent = Objects.requireNonNull(userAgent, "userAgent");
        return this;
    }

    /**
     * Supplies a mapper. It is copied and the required settings are applied to the copy; the
     * given instance is never modified.
     */
    public SleeperClientBuilder objectMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        return this;
    }

    /** Sets the retry policy. Default {@link RetryPolicy#defaults()}; use {@link RetryPolicy#none()} to disable. */
    public SleeperClientBuilder retryPolicy(RetryPolicy retryPolicy) {
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        return this;
    }

    /** Sets the client-side rate limit. Default {@link RateLimit#defaults()}; {@link RateLimit#disabled()} turns it off. */
    public SleeperClientBuilder rateLimit(RateLimit rateLimit) {
        this.rateLimit = Objects.requireNonNull(rateLimit, "rateLimit");
        return this;
    }

    /** Sets the clock used by time-dependent components. Default UTC system clock. */
    public SleeperClientBuilder clock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        return this;
    }

    /**
     * Validates the configuration and builds the client.
     *
     * @throws IllegalArgumentException if the base URI is not absolute http/https, a timeout is not
     *     positive, or the user agent is blank
     */
    public SleeperClient build() {
        URI base = parseBaseUri(baseUri);
        requirePositive(requestTimeout, "requestTimeout");
        if (userAgent.isBlank()) {
            throw new IllegalArgumentException("userAgent must not be blank");
        }
        HttpClient client = httpClient != null ? httpClient : defaultHttpClient();
        ObjectMapper mapper = objectMapper != null
                ? JsonCodec.copyOf(objectMapper) : JsonCodec.create();
        Requester requester = new Requester(client, base, mapper, requestTimeout, userAgent,
                retryPolicy, clock, Sleeper.real(), threadSafeRandom(),
                new RateLimiter(rateLimit, clock, Sleeper.real()));
        return new SleeperClient(requester, clock);
    }

    /** ThreadLocalRandom must be fetched per thread, so look it up on every call. */
    private static RandomGenerator threadSafeRandom() {
        return () -> ThreadLocalRandom.current().nextLong();
    }

    private static URI parseBaseUri(String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("baseUri must not be blank");
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("baseUri is not a valid URI: " + value, e);
        }
        String scheme = uri.getScheme();
        boolean http = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (!http || uri.getHost() == null) {
            throw new IllegalArgumentException("baseUri must be an absolute http or https URI: " + value);
        }
        return uri;
    }

    private static void requirePositive(Duration d, String name) {
        if (d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive: " + d);
        }
    }

    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                .build();
    }

    private static String version() {
        String v = SleeperClient.class.getPackage().getImplementationVersion();
        return v == null || v.isBlank() ? UNKNOWN_VERSION : v;
    }
}
