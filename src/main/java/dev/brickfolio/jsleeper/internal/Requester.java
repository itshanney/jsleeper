package dev.brickfolio.jsleeper.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.brickfolio.jsleeper.RetryPolicy;
import dev.brickfolio.jsleeper.error.SleeperApiException;
import dev.brickfolio.jsleeper.error.SleeperDeserializationException;
import dev.brickfolio.jsleeper.error.SleeperException;
import dev.brickfolio.jsleeper.error.SleeperTransportException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Runs one GET end to end: builds the URI, sends it, classifies the response by the empty-result
 * rules, and deserialises the body. Retryable failures are retried per the {@link RetryPolicy}, and every
 * attempt first takes a token from the {@link RateLimiter}. No listeners yet.
 *
 * <p>Response bodies are read fully into memory before parsing. The largest response (players,
 * about 5 MB) fits comfortably, and it makes error snippets trivial.
 */
public final class Requester {

    private static final String METHOD = "GET";
    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final int STATUS_OK_MIN = 200;
    private static final int STATUS_OK_MAX = 299;
    private static final int STATUS_NOT_FOUND = 404;
    private static final int ERROR_BODY_LIMIT_BYTES = 4096;
    // Decoded then truncated to 512 bytes by the exception types; a little slack avoids a split char.
    private static final int SNIPPET_DECODE_BYTES = 1024;

    private final HttpClient httpClient;
    private final String baseUri;
    private final ObjectMapper mapper;
    private final Duration requestTimeout;
    private final String userAgent;
    private final RetryPolicy retryPolicy;
    private final Clock clock;
    private final Sleeper sleeper;
    private final RateLimiter rateLimiter;
    private final RandomGenerator random;

    /**
     * @param baseUri API root such as {@code https://api.sleeper.app/v1}
     * @param requestTimeout default per-request timeout; must be positive
     * @param clock resolves HTTP-date {@code Retry-After} values
     * @param sleeper waits between attempts
     * @param random supplies backoff jitter
     * @param rateLimiter takes a token before every attempt, retries included
     */
    public Requester(HttpClient httpClient, URI baseUri, ObjectMapper mapper,
            Duration requestTimeout, String userAgent, RetryPolicy retryPolicy, Clock clock,
            Sleeper sleeper, RandomGenerator random, RateLimiter rateLimiter) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        String base = Objects.requireNonNull(baseUri, "baseUri").toString();
        this.baseUri = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        this.userAgent = Objects.requireNonNull(userAgent, "userAgent");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.random = Objects.requireNonNull(random, "random");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
    }

    /**
     * Performs a GET.
     *
     * @param pathSegments unencoded path segments; each is percent-encoded individually
     * @param query unencoded query parameters, may be empty
     * @param type for {@code OPTIONAL} the element type {@code T}; otherwise the full result type
     * @param kind how an absent resource is reported
     * @param timeoutOverride per-request timeout replacing the default, or null
     * @return {@code Optional<T>} for OPTIONAL, otherwise the parsed value (or an empty collection)
     */
    @SuppressWarnings("unchecked")
    public <R> R get(List<String> pathSegments, Map<String, String> query, JavaType type,
            EmptyKind kind, Duration timeoutOverride) {
        String path = encodePath(pathSegments);
        HttpRequest request = buildRequest(path, query,
                timeoutOverride == null ? requestTimeout : requirePositive(timeoutOverride, "timeoutOverride"));

        for (int attempt = 1; ; attempt++) {
            // Outside the try: a client-side rate-limit failure must not itself be retried.
            rateLimiter.acquire(METHOD, path);
            RetryHint hint = new RetryHint();
            try {
                return (R) attemptOnce(request, path, type, kind, hint);
            } catch (SleeperException failure) {
                if (attempt >= retryPolicy.maxAttempts() || !isRetryable(failure)) {
                    throw failure;
                }
                Duration delay = delayBeforeRetry(attempt, hint);
                if (delay == null) {
                    // Retry-After asks for longer than we are willing to block.
                    throw failure;
                }
                pause(delay, path);
            }
        }
    }

    /** Carries the response's {@code Retry-After} header out of a failed attempt. */
    private static final class RetryHint {
        private String retryAfter;
    }

    private Object attemptOnce(HttpRequest request, String path, JavaType type, EmptyKind kind,
            RetryHint hint) {
        HttpResponse<InputStream> response = send(request, path);
        int status = response.statusCode();
        try (InputStream body = response.body()) {
            if (status >= STATUS_OK_MIN && status <= STATUS_OK_MAX) {
                byte[] bytes = readAll(body, path);
                return parseSuccess(bytes, path, type, kind, status);
            }
            byte[] bytes = readLimited(body, path);
            if (status == STATUS_NOT_FOUND && isJson(response)) {
                return emptyResult(kind, path, status, bytes);
            }
            hint.retryAfter = response.headers().firstValue("Retry-After").orElse(null);
            // A non-JSON 404 is an unknown route (a library bug or API change), never "no data".
            throw SleeperApiException.forStatus(METHOD, path, status, snippet(bytes));
        } catch (IOException e) {
            throw new SleeperTransportException(METHOD, path, e);
        }
    }

    private boolean isRetryable(SleeperException failure) {
        if (failure instanceof SleeperApiException api) {
            return retryPolicy.retryOnStatuses().contains(api.status());
        }
        if (failure instanceof SleeperTransportException transport) {
            // An interrupted thread must stop, not retry.
            return retryPolicy.retryOnTransportErrors()
                    && !(transport.getCause() instanceof InterruptedException);
        }
        return false;
    }

    /** Returns the wait before the next attempt, or null if {@code Retry-After} is too long. */
    private Duration delayBeforeRetry(int failedAttempt, RetryHint hint) {
        Duration retryAfter = parseRetryAfter(hint.retryAfter);
        if (retryAfter != null) {
            return retryAfter.compareTo(RetryPolicy.MAX_RETRY_AFTER) > 0 ? null : retryAfter;
        }
        long ceilingMillis = retryPolicy.backoffCeiling(failedAttempt).toMillis();
        return Duration.ofMillis(random.nextLong(ceilingMillis + 1));
    }

    /** Parses integer seconds or an HTTP date; returns null if absent or unparseable. */
    private Duration parseRetryAfter(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();
        try {
            return Duration.ofSeconds(Math.max(Long.parseLong(value), 0));
        } catch (NumberFormatException notSeconds) {
            // fall through to the HTTP-date form
        }
        try {
            Instant when = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration wait = Duration.between(clock.instant(), when);
            return wait.isNegative() ? Duration.ZERO : wait;
        } catch (DateTimeParseException notDate) {
            return null;
        }
    }

    private void pause(Duration delay, String path) {
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SleeperTransportException(METHOD, path, e);
        }
    }

    private Object parseSuccess(byte[] bytes, String path, JavaType type, EmptyKind kind, int status) {
        if (isBlank(bytes)) {
            return emptyResult(kind, path, status, bytes);
        }
        Object value;
        try {
            value = mapper.readValue(bytes, type);
        } catch (JsonProcessingException e) {
            throw new SleeperDeserializationException(METHOD, path, jsonPath(e), snippet(bytes), e);
        } catch (IOException e) {
            throw new SleeperDeserializationException(METHOD, path, null, snippet(bytes), e);
        }
        if (value == null) {
            return emptyResult(kind, path, status, bytes);
        }
        return kind == EmptyKind.OPTIONAL ? Optional.of(value) : value;
    }

    private static Object emptyResult(EmptyKind kind, String path, int status, byte[] body) {
        return switch (kind) {
            case OPTIONAL -> Optional.empty();
            case LIST -> List.of();
            case MAP -> Map.of();
            case REQUIRED -> throw SleeperApiException.forStatus(METHOD, path, status, snippet(body));
        };
    }

    private HttpRequest buildRequest(String path, Map<String, String> query, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(baseUri + path + encodeQuery(query)))
                .GET()
                .timeout(timeout)
                .header("Accept", JSON_CONTENT_TYPE)
                .header("User-Agent", userAgent)
                .build();
    }

    private HttpResponse<InputStream> send(HttpRequest request, String path) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new SleeperTransportException(METHOD, path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SleeperTransportException(METHOD, path, e);
        }
    }

    private static byte[] readAll(InputStream in, String path) {
        try {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new SleeperTransportException(METHOD, path, e);
        }
    }

    private static byte[] readLimited(InputStream in, String path) {
        try {
            return in.readNBytes(ERROR_BODY_LIMIT_BYTES);
        } catch (IOException e) {
            throw new SleeperTransportException(METHOD, path, e);
        }
    }

    private static boolean isJson(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type")
                .map(v -> v.trim().toLowerCase(Locale.ROOT).startsWith(JSON_CONTENT_TYPE))
                .orElse(false);
    }

    private static boolean isBlank(byte[] bytes) {
        for (byte b : bytes) {
            if (b != ' ' && b != '\t' && b != '\n' && b != '\r') {
                return false;
            }
        }
        return true;
    }

    private static String snippet(byte[] bytes) {
        return new String(bytes, 0, Math.min(bytes.length, SNIPPET_DECODE_BYTES), StandardCharsets.UTF_8);
    }

    private static String jsonPath(JsonProcessingException e) {
        if (!(e instanceof JsonMappingException mapping) || mapping.getPath().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("$");
        for (JsonMappingException.Reference ref : mapping.getPath()) {
            if (ref.getFieldName() != null) {
                sb.append('.').append(ref.getFieldName());
            } else if (ref.getIndex() >= 0) {
                sb.append('[').append(ref.getIndex()).append(']');
            }
        }
        return sb.toString();
    }

    private static String encodePath(List<String> segments) {
        StringBuilder sb = new StringBuilder();
        for (String segment : segments) {
            sb.append('/').append(UrlEncoding.encode(segment));
        }
        return sb.toString();
    }

    private static String encodeQuery(Map<String, String> query) {
        if (query.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        char separator = '?';
        for (Map.Entry<String, String> e : query.entrySet()) {
            sb.append(separator).append(UrlEncoding.encode(e.getKey())).append('=')
                    .append(UrlEncoding.encode(e.getValue()));
            separator = '&';
        }
        return sb.toString();
    }

    private static Duration requirePositive(Duration d, String name) {
        Objects.requireNonNull(d, name);
        if (d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive: " + d);
        }
        return d;
    }
}
