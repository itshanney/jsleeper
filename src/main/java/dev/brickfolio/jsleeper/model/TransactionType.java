package dev.brickfolio.jsleeper.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Kind of roster transaction. Open enum: unrecognised values read as {@link #UNKNOWN}. */
public enum TransactionType implements WireEnum {
    /** A trade between rosters. */
    @JsonProperty("trade") TRADE("trade"),
    /** A free-agent add or drop. */
    @JsonProperty("free_agent") FREE_AGENT("free_agent"),
    /** A waiver claim. */
    @JsonProperty("waiver") WAIVER("waiver"),
    /** Any value this library does not know. */
    @JsonEnumDefaultValue UNKNOWN("unknown");

    private final String wireValue;

    TransactionType(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    public String wireValue() {
        return wireValue;
    }

    /** Returns the constant for a wire string, or {@link #UNKNOWN}. */
    public static TransactionType fromWire(String wire) {
        return WireEnum.fromWire(TransactionType.class, wire, UNKNOWN);
    }
}
