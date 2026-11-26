package dev.brickfolio.jsleeper.error;

/** HTTP 5xx (500, 503, and others). */
public class SleeperServerException extends SleeperApiException {

    private static final long serialVersionUID = 1L;

    /** Creates an exception for a 5xx response. */
    public SleeperServerException(String method, String path, int status, String bodySnippet) {
        super(method, path, status, bodySnippet);
    }
}
