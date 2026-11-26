package dev.brickfolio.jsleeper.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Lifecycle state of a league. Open enum: unrecognised values read as {@link #UNKNOWN}. */
public enum LeagueStatus implements WireEnum {
    /** The draft has not started. */
    @JsonProperty("pre_draft") PRE_DRAFT("pre_draft"),
    /** The draft is in progress. */
    @JsonProperty("drafting") DRAFTING("drafting"),
    /** The season is under way. */
    @JsonProperty("in_season") IN_SEASON("in_season"),
    /** The season is over. */
    @JsonProperty("complete") COMPLETE("complete"),
    /** Any value this library does not know. */
    @JsonEnumDefaultValue UNKNOWN("unknown");

    private final String wireValue;

    LeagueStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    public String wireValue() {
        return wireValue;
    }

    /** Returns the constant for a wire string, or {@link #UNKNOWN}. */
    public static LeagueStatus fromWire(String wire) {
        return WireEnum.fromWire(LeagueStatus.class, wire, UNKNOWN);
    }
}
