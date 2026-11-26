package dev.brickfolio.jsleeper.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Draft state. Open enum: unrecognised values read as {@link #UNKNOWN}. */
public enum DraftStatus implements WireEnum {
    /** Not started. */
    @JsonProperty("pre_draft") PRE_DRAFT("pre_draft"),
    /** In progress. */
    @JsonProperty("drafting") DRAFTING("drafting"),
    /** Paused. */
    @JsonProperty("paused") PAUSED("paused"),
    /** Finished. */
    @JsonProperty("complete") COMPLETE("complete"),
    /** Any value this library does not know. */
    @JsonEnumDefaultValue UNKNOWN("unknown");

    private final String wireValue;

    DraftStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    public String wireValue() {
        return wireValue;
    }

    /** Returns the constant for a wire string, or {@link #UNKNOWN}. */
    public static DraftStatus fromWire(String wire) {
        return WireEnum.fromWire(DraftStatus.class, wire, UNKNOWN);
    }
}
