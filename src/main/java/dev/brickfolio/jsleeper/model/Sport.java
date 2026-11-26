package dev.brickfolio.jsleeper.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A sport Sleeper supports. Open enum: unrecognised values read as {@link #UNKNOWN}. */
public enum Sport implements WireEnum {
    /** American football. */
    @JsonProperty("nfl") NFL("nfl"),
    /** Basketball. */
    @JsonProperty("nba") NBA("nba"),
    /** League of Legends esports. */
    @JsonProperty("lcs") LCS("lcs"),
    /** Any value this library does not know; cannot be used in a request path. */
    @JsonEnumDefaultValue UNKNOWN("unknown");

    private final String wireValue;

    Sport(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    public String wireValue() {
        return wireValue;
    }

    /**
     * Returns the constant for a wire string, or {@link #UNKNOWN}.
     *
     * @return the matching constant, or {@code UNKNOWN} if unrecognised
     * @param wire the raw wire string, may be null
     */
    public static Sport fromWire(String wire) {
        return WireEnum.fromWire(Sport.class, wire, UNKNOWN);
    }
}
