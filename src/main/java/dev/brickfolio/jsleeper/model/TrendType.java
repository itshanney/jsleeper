package dev.brickfolio.jsleeper.model;

/**
 * Which trending list to fetch. Input only, so there is no UNKNOWN constant: Sleeper answers an
 * invalid type with an empty list, which an enum prevents.
 */
public enum TrendType implements WireEnum {
    /** Most added players. */
    ADD("add"),
    /** Most dropped players. */
    DROP("drop");

    private final String wireValue;

    TrendType(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    public String wireValue() {
        return wireValue;
    }
}
