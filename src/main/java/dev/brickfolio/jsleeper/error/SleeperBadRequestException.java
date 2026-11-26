package dev.brickfolio.jsleeper.error;

/** HTTP 400. */
public class SleeperBadRequestException extends SleeperApiException {

    private static final long serialVersionUID = 1L;

    private static final int STATUS = 400;

    /** Creates an exception for a 400 response. */
    public SleeperBadRequestException(String method, String path, String bodySnippet) {
        super(method, path, STATUS, bodySnippet);
    }
}
