package dev.brickfolio.jsleeper.api;

import dev.brickfolio.jsleeper.model.Sport;

/** Argument validation shared by the facades; runs before any network call. */
final class Args {

    private Args() {}

    static String requireId(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static Sport requireSport(Sport sport) {
        if (sport == null || sport == Sport.UNKNOWN) {
            throw new IllegalArgumentException("sport must be a known sport, was " + sport);
        }
        return sport;
    }

    static int requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
        return value;
    }
}
