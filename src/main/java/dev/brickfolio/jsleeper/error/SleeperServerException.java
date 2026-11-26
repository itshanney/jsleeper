package dev.brickfolio.jsleeper.error;

/** HTTP 5xx (500, 503, and others). */
public class SleeperServerException extends SleeperApiException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception for a 5xx response.
     *
     * @param method the HTTP method of the failed request
     * @param path the request path of the failed request
     * @param status the HTTP status code
     * @param bodySnippet the response body, truncated to 512 UTF-8 bytes; may be null
     */
    public SleeperServerException(String method, String path, int status, String bodySnippet) {
        super(method, path, status, bodySnippet);
    }
}
