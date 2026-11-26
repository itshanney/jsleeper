package dev.brickfolio.jsleeper;

/**
 * Passed to {@link RequestListener#onRequest} just before each attempt is sent.
 *
 * @param method HTTP method
 * @param pathTemplate path with named placeholders, such as {@code /league/{league_id}/matchups/{week}};
 *     safe as a low-cardinality metrics label
 * @param path concrete, percent-encoded path of this request
 * @param attempt 1 for the first attempt, 2 for the first retry, and so on
 */
public record RequestEvent(String method, String pathTemplate, String path, int attempt) {}
