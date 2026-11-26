package dev.brickfolio.jsleeper.error;

/** A non-2xx response (after retries). */
public class SleeperApiException extends SleeperException {

    private static final long serialVersionUID = 1L;

    private static final int STATUS_BAD_REQUEST = 400;
    private static final int STATUS_TOO_MANY_REQUESTS = 429;
    private static final int STATUS_SERVER_ERROR_MIN = 500;
    private static final int STATUS_SERVER_ERROR_MAX = 599;

    /** HTTP status code of the response. */
    private final int status;
    /** First 512 bytes of the response body, or null. */
    private final String bodySnippet;

    /**
     * Creates the exception.
     * @param method HTTP method
     * @param path request path
     * @param status HTTP status code
     * @param bodySnippet response body, truncated to 512 UTF-8 bytes; may be null
     */
    public SleeperApiException(String method, String path, int status, String bodySnippet) {
        super(method, path, describe(method, path, status, BodySnippets.truncate(bodySnippet)), null);
        this.status = status;
        this.bodySnippet = BodySnippets.truncate(bodySnippet);
    }

    SleeperApiException(String method, String path, int status, String bodySnippet, String message) {
        super(method, path, message, null);
        this.status = status;
        this.bodySnippet = BodySnippets.truncate(bodySnippet);
    }

    /**
     * Picks the exception type for a status: 400 to {@link SleeperBadRequestException}, 429 to
     * {@link SleeperRateLimitException} (server-side), 5xx to {@link SleeperServerException},
     * anything else to this base class.
     *
     * @param body response body; truncated to 512 UTF-8 bytes, may be null
     * @return the exception for the status
     * @param method the HTTP method of the failed request
     * @param path the request path of the failed request
     * @param status the HTTP status code
     */
    public static SleeperApiException forStatus(String method, String path, int status, String body) {
        if (status == STATUS_BAD_REQUEST) {
            return new SleeperBadRequestException(method, path, body);
        }
        if (status == STATUS_TOO_MANY_REQUESTS) {
            return new SleeperRateLimitException(method, path, body);
        }
        if (status >= STATUS_SERVER_ERROR_MIN && status <= STATUS_SERVER_ERROR_MAX) {
            return new SleeperServerException(method, path, status, body);
        }
        return new SleeperApiException(method, path, status, body);
    }

    /**
     * Returns the HTTP status code.
     *
     * @return the HTTP status code
     */
    public int status() {
        return status;
    }

    /**
     * Returns the first 512 bytes of the response body, or null if there was no body.
     *
     * @return the body snippet, or null
     */
    public String bodySnippet() {
        return bodySnippet;
    }

    static String describe(String method, String path, int status, String snippet) {
        return method + " " + path + " -> " + status + (snippet == null ? "" : ": " + snippet);
    }
}
