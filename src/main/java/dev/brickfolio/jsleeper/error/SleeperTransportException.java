package dev.brickfolio.jsleeper.error;

import java.util.Objects;

/** I/O failure, timeout, connection refused, or interruption. The cause is always preserved. */
public class SleeperTransportException extends SleeperException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     * @param cause the underlying failure; must not be null
     * @param method the HTTP method of the failed request
     * @param path the request path of the failed request
     */
    public SleeperTransportException(String method, String path, Throwable cause) {
        super(method, path,
                method + " " + path + " failed: " + Objects.requireNonNull(cause, "cause"), cause);
    }
}
