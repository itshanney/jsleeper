package dev.brickfolio.jsleeper.error;

/** A 2xx response whose body could not be parsed into the expected type. */
public class SleeperDeserializationException extends SleeperException {

    private static final long serialVersionUID = 1L;

    /** JSON path of the failure, or null. */
    private final String jsonPath;
    /** First 512 bytes of the response body, or null. */
    private final String bodySnippet;

    /**
     * Creates the exception.
     * @param jsonPath location of the failure within the document, may be null if unknown
     * @param bodySnippet response body, truncated to 512 UTF-8 bytes; may be null
     * @param cause the parser failure, may be null
     * @param method the HTTP method of the failed request
     * @param path the request path of the failed request
     */
    public SleeperDeserializationException(String method, String path, String jsonPath,
            String bodySnippet, Throwable cause) {
        super(method, path, method + " " + path + " returned an unparseable body"
                + (jsonPath == null ? "" : " at " + jsonPath), cause);
        this.jsonPath = jsonPath;
        this.bodySnippet = BodySnippets.truncate(bodySnippet);
    }

    /**
     * Returns the JSON path of the failure, or null if unknown.
     *
     * @return the JSON path, or null
     */
    public String jsonPath() {
        return jsonPath;
    }

    /**
     * Returns the first 512 bytes of the response body, or null if unavailable.
     *
     * @return the body snippet, or null
     */
    public String bodySnippet() {
        return bodySnippet;
    }
}
