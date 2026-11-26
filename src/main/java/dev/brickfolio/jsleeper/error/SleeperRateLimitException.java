package dev.brickfolio.jsleeper.error;

/**
 * HTTP 429 from Sleeper, or the client-side limiter giving up before sending anything. Both report
 * status 429 so callers can handle them identically; use {@link #isClientSide()} to tell them apart.
 */
public class SleeperRateLimitException extends SleeperApiException {

    private static final long serialVersionUID = 1L;

    private static final int STATUS = 429;

    private final boolean clientSide;

    /** Creates an exception for a 429 response from the server. */
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
     */
    public static SleeperRateLimitException clientSide(String method, String path) {
        return new SleeperRateLimitException(method, path,
                method + " " + path + " not sent: client-side rate limit wait exceeded", true);
    }

    /** Returns true if the local limiter timed out and the request was never sent. */
    public boolean isClientSide() {
        return clientSide;
    }
}
