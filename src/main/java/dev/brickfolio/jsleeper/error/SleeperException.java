package dev.brickfolio.jsleeper.error;

import java.util.Objects;

/** Base class of all exceptions thrown by the client. Carries the failed request's method and path. */
public class SleeperException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** HTTP method of the failed request. */
    private final String method;
    /** Path of the failed request. */
    private final String path;

    /**
     * Creates the exception.
     * @param method HTTP method of the failed request
     * @param path request path of the failed request
     * @param message detail message
     * @param cause underlying cause, may be null
     */
    public SleeperException(String method, String path, String message, Throwable cause) {
        super(message, cause);
        this.method = Objects.requireNonNull(method, "method");
        this.path = Objects.requireNonNull(path, "path");
    }

    /**
     * Returns the HTTP method of the failed request.
     *
     * @return the HTTP method
     */
    public String method() {
        return method;
    }

    /**
     * Returns the path of the failed request.
     *
     * @return the request path
     */
    public String path() {
        return path;
    }
}
