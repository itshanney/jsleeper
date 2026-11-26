package dev.brickfolio.jsleeper.error;

/**
 * HTTP 429 from Sleeper, or the client-side limiter giving up before sending anything. Both report
 * status 429 so callers can handle them identically; use {@link #isClientSide()} to tell them apart.
 */
public class SleeperRateLimitException extends SleeperApiException {

    private static final long serialVersionUID = 1L;

    private static final int STATUS = 429;

    /** True if the local limiter gave up before anything was sent. */
    private final boolean clientSide;

    /**
     * Creates an exception for a 429 response from the server.
     *
     * @param method the HTTP method of the failed request
     * @param path the request path of the failed request
     * @param bodySnippet the response body, truncated to 512 UTF-8 bytes; may be null
     */
    public SleeperRateLimitException(String method, String path, String bodySnippet) {
        super(method, path, STATUS, bodySnippet);
        this.clientSide = false;
    }

    private SleeperRateLimitException(String method, String path, String message, boolean clientSide) {
        super(method, path, STATUS, null, message);
        this.clientSide = clientSide;
    }

    /**
     * Creates the exception thrown when the local rate limiter could not grant a token in time.
     * Nothing was sent to Sleeper.
     *
     * @return the client-side exception
     * @param method the HTTP method of the failed request
     * @param path the request path of the failed request
     */
    public static SleeperRateLimitException clientSide(String method, String path) {
        return new SleeperRateLimitException(method, path,
                method + " " + path + " not sent: client-side rate limit wait exceeded", true);
    }

    /**
     * Returns true if the local limiter timed out and the request was never sent.
     *
     * @return true if the local limiter gave up and nothing was sent
     */
    public boolean isClientSide() {
        return clientSide;
    }
}
