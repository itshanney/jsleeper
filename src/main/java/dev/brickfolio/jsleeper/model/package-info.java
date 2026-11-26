/**
 * Immutable models for Sleeper responses.
 *
 * <h2>Open-enum convention</h2>
 * Sleeper adds values without notice, so every enum is open:
 * <ul>
 *   <li>The enum implements {@link dev.brickfolio.jsleeper.model.WireEnum}.</li>
 *   <li>Each constant carries {@code @JsonProperty("wire_value")} with the exact wire string.</li>
 *   <li>The last constant is {@code @JsonEnumDefaultValue UNKNOWN}; the mapper reads any
 *       unrecognised value as {@code UNKNOWN} instead of failing.</li>
 *   <li>Any record that has an enum field also keeps the raw wire string next to it (for example
 *       {@code status} and {@code statusRaw}), so a value that mapped to {@code UNKNOWN} can still
 *       be read.</li>
 * </ul>
 * <h2>Keeping the raw string</h2>
 * An enum has a static {@code fromWire(String)} that delegates to
 * {@link dev.brickfolio.jsleeper.model.WireEnum#fromWire(Class, String, Enum)} and returns
 * {@code UNKNOWN} for anything unrecognised. A record with an enum field declares a
 * {@code @JsonCreator} static factory that takes the wire value as a {@code String}, stores it
 * (for example {@code seasonTypeRaw}) and derives the enum with {@code fromWire}. The factory
 * exists because one JSON property feeds two record components.
 * <p>
 * Jackson reads these records reflectively, so every package under {@code model} must be
 * {@code opens}ed to {@code com.fasterxml.jackson.databind} in {@code module-info.java}.
 */
package dev.brickfolio.jsleeper.model;
