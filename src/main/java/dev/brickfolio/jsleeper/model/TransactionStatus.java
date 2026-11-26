package dev.brickfolio.jsleeper.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Outcome of a transaction. Open enum: unrecognised values read as {@link #UNKNOWN}. */
public enum TransactionStatus implements WireEnum {
    /** The transaction went through. */
    @JsonProperty("complete") COMPLETE("complete"),
    /** The transaction failed, for example a lost waiver claim. */
    @JsonProperty("failed") FAILED("failed"),
    /** Any value this library does not know. */
    @JsonEnumDefaultValue UNKNOWN("unknown");

    private final String wireValue;

    TransactionStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    public String wireValue() {
        return wireValue;
    }

    /** Returns the constant for a wire string, or {@link #UNKNOWN}. */
    public static TransactionStatus fromWire(String wire) {
        return WireEnum.fromWire(TransactionStatus.class, wire, UNKNOWN);
    }
}
