package dev.brickfolio.jsleeper;

/**
 * Hook for metrics and logging. Both methods run on the calling thread, once per attempt, so
 * implementations must be fast and thread-safe. An exception thrown by a listener is caught and
 * logged at WARNING; it never fails or changes the request. To use several listeners, compose them
 * in one implementation.
 */
public interface RequestListener {

    /** Called before each attempt is sent. Does nothing by default. */
    default void onRequest(RequestEvent event) {}

    /** Called after each attempt finishes. Does nothing by default. */
    default void onResponse(ResponseEvent event) {}

    /** Returns a listener that does nothing. */
    static RequestListener noop() {
        return new RequestListener() {};
    }
}
