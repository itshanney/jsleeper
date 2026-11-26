package dev.brickfolio.jsleeper.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Draft format. Open enum: unrecognised values read as {@link #UNKNOWN}. */
public enum DraftType implements WireEnum {
    /** Order reverses each round. */
    @JsonProperty("snake") SNAKE("snake"),
    /** Same order every round. */
    @JsonProperty("linear") LINEAR("linear"),
    /** Nomination and bidding. */
    @JsonProperty("auction") AUCTION("auction"),
    /** Any value this library does not know. */
    @JsonEnumDefaultValue UNKNOWN("unknown");

    private final String wireValue;

    DraftType(String wireValue) {
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
    public static DraftType fromWire(String wire) {
        return WireEnum.fromWire(DraftType.class, wire, UNKNOWN);
    }
}
