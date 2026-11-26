package dev.brickfolio.jsleeper.error;

import java.util.Objects;

/** I/O failure, timeout, connection refused, or interruption. The cause is always preserved. */
public class SleeperTransportException extends SleeperException {

    private static final long serialVersionUID = 1L;

    /**
     * @param cause the underlying failure; must not be null
     */
    public SleeperTransportException(String method, String path, Throwable cause) {
        super(method, path,
                method + " " + path + " failed: " + Objects.requireNonNull(cause, "cause"), cause);
    }
}
