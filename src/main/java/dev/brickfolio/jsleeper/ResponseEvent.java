package dev.brickfolio.jsleeper;

import dev.brickfolio.jsleeper.error.SleeperException;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Passed to {@link RequestListener#onResponse} after each attempt finishes, successfully or not.
 *
 * @param method HTTP method
 * @param pathTemplate path with named placeholders, such as {@code /league/{league_id}}
 * @param path concrete, percent-encoded path of this request
 * @param attempt 1 for the first attempt, 2 for the first retry, and so on
 * @param status HTTP status; empty if no response arrived (a transport failure)
 * @param duration time from sending to finishing the attempt, including reading the body
 * @param bytes response body bytes read; error bodies are read only up to a small cap
 * @param error the failure of this attempt, or empty if it succeeded
 * @param willRetry true if the client will make another attempt after this one
 * @param retryReason why a retry follows, such as {@code "status 503"} or {@code "transport"};
 *     empty when {@code willRetry} is false
 */
public record ResponseEvent(
        String method,
        String pathTemplate,
        String path,
        int attempt,
        OptionalInt status,
        Duration duration,
        long bytes,
        Optional<SleeperException> error,
        boolean willRetry,
        Optional<String> retryReason) {}
