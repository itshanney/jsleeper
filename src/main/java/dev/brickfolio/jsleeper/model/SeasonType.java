package dev.brickfolio.jsleeper.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Phase of a season. Open enum: unrecognised values read as {@link #UNKNOWN}. */
public enum SeasonType implements WireEnum {
    /** Pre-season. */
    @JsonProperty("pre") PRE("pre"),
    /** Regular season. */
    @JsonProperty("regular") REGULAR("regular"),
    /** Post-season. */
    @JsonProperty("post") POST("post"),
    /** Off-season (not in the docs, but seen live). */
    @JsonProperty("off") OFF("off"),
    /** Any value this library does not know. */
    @JsonEnumDefaultValue UNKNOWN("unknown");

    private final String wireValue;

    SeasonType(String wireValue) {
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
    public static SeasonType fromWire(String wire) {
        return WireEnum.fromWire(SeasonType.class, wire, UNKNOWN);
    }
}
