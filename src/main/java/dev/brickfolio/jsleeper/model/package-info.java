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
 * Jackson reads these records reflectively, so every package under {@code model} must be
 * {@code opens}ed to {@code com.fasterxml.jackson.databind} in {@code module-info.java}.
 */
package dev.brickfolio.jsleeper.model;
