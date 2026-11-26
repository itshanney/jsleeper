package dev.brickfolio.jsleeper.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.brickfolio.jsleeper.error.SleeperApiException;
import dev.brickfolio.jsleeper.error.SleeperDeserializationException;
import dev.brickfolio.jsleeper.error.SleeperTransportException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs one GET end to end: builds the URI, sends it, classifies the response by the empty-result
 * rules, and deserialises the body. No retries, rate limiting or listeners yet.
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

    /**
     * @param baseUri API root such as {@code https://api.sleeper.app/v1}
     * @param requestTimeout default per-request timeout; must be positive
     */
    public Requester(HttpClient httpClient, URI baseUri, ObjectMapper mapper,
            Duration requestTimeout, String userAgent) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        String base = Objects.requireNonNull(baseUri, "baseUri").toString();
        this.baseUri = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        this.userAgent = Objects.requireNonNull(userAgent, "userAgent");
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

        HttpResponse<InputStream> response = send(request, path);
        int status = response.statusCode();
        try (InputStream body = response.body()) {
            if (status >= STATUS_OK_MIN && status <= STATUS_OK_MAX) {
                byte[] bytes = readAll(body, path);
                return (R) parseSuccess(bytes, path, type, kind, status);
            }
            byte[] bytes = readLimited(body, path);
            if (status == STATUS_NOT_FOUND && isJson(response)) {
                return (R) emptyResult(kind, path, status, bytes);
            }
            // A non-JSON 404 is an unknown route (a library bug or API change), never "no data".
            throw SleeperApiException.forStatus(METHOD, path, status, snippet(bytes));
        } catch (IOException e) {
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
