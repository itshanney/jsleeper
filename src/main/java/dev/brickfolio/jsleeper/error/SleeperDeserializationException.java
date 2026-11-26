package dev.brickfolio.jsleeper.error;

/** A 2xx response whose body could not be parsed into the expected type. */
public class SleeperDeserializationException extends SleeperException {

    private static final long serialVersionUID = 1L;

    private final String jsonPath;
    private final String bodySnippet;

    /**
     * @param jsonPath location of the failure within the document, may be null if unknown
     * @param bodySnippet response body, truncated to 512 UTF-8 bytes; may be null
     * @param cause the parser failure, may be null
     */
    public SleeperDeserializationException(String method, String path, String jsonPath,
            String bodySnippet, Throwable cause) {
        super(method, path, method + " " + path + " returned an unparseable body"
                + (jsonPath == null ? "" : " at " + jsonPath), cause);
        this.jsonPath = jsonPath;
        this.bodySnippet = BodySnippets.truncate(bodySnippet);
    }

    /** Returns the JSON path of the failure, or null if unknown. */
    public String jsonPath() {
        return jsonPath;
    }

    /** Returns the first 512 bytes of the response body, or null if unavailable. */
    public String bodySnippet() {
        return bodySnippet;
    }
}
