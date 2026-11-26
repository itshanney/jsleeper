package dev.brickfolio.jsleeper.error;

/** HTTP 400. */
public class SleeperBadRequestException extends SleeperApiException {

    private static final long serialVersionUID = 1L;

    private static final int STATUS = 400;

    /**
     * Creates an exception for a 400 response.
     *
     * @param method the HTTP method of the failed request
     * @param path the request path of the failed request
     * @param bodySnippet the response body, truncated to 512 UTF-8 bytes; may be null
     */
    public SleeperBadRequestException(String method, String path, String bodySnippet) {
        super(method, path, STATUS, bodySnippet);
    }
}
