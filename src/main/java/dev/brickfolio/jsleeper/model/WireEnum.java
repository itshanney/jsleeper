package dev.brickfolio.jsleeper.model;

/**
 * Implemented by every enum that mirrors a value set on the Sleeper wire.
 */
public interface WireEnum {

    /** Returns the exact string Sleeper uses for this constant; {@code UNKNOWN} returns "unknown". */
    String wireValue();

    /**
     * Looks up the constant whose {@link #wireValue()} equals {@code wire}.
     * Enums expose this through their own {@code static E fromWire(String)}.
     *
     * @param type the enum class
     * @param wire the raw wire string, may be null
     * @param unknown the constant returned for null or unrecognised values
     */
    static <E extends Enum<E> & WireEnum> E fromWire(Class<E> type, String wire, E unknown) {
        if (wire != null) {
            for (E constant : type.getEnumConstants()) {
                if (constant.wireValue().equals(wire)) {
                    return constant;
                }
            }
        }
        return unknown;
    }
}
