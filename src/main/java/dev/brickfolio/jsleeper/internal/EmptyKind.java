package dev.brickfolio.jsleeper.internal;

/** What a request returns when Sleeper reports that the resource is absent. */
public enum EmptyKind {
    /** Absent resource gives {@code Optional.empty()}; a present one is wrapped in an Optional. */
    OPTIONAL,
    /** Absent resource gives {@code List.of()}. */
    LIST,
    /** Absent resource gives {@code Map.of()}. */
    MAP,
    /** Absent resource is an error: {@code SleeperApiException}. */
    REQUIRED
}
