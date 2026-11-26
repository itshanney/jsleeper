package dev.brickfolio.jsleeper.model;

/**
 * Implemented by every enum that mirrors a value set on the Sleeper wire.
 */
public interface WireEnum {

    /** Returns the exact string Sleeper uses for this constant; {@code UNKNOWN} returns "unknown". */
    String wireValue();
}
