package dev.brickfolio.jsleeper.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import java.util.Objects;

/**
 * Builds the tolerant {@link ObjectMapper} implementing the global mapping rules: snake_case
 * names, unknown properties and enum values tolerated, epoch-millisecond timestamps, and scalar
 * coercion (for example the string {@code "10"} into an {@code Integer}).
 */
public final class JsonCodec {

    private static final List<LogicalType> COERCED_SCALARS =
            List.of(LogicalType.Integer, LogicalType.Float, LogicalType.Boolean);

    private JsonCodec() {}

    /** Returns a new mapper configured with the required settings. */
    public static ObjectMapper create() {
        return apply(JsonMapper.builder().build());
    }

    /**
     * Copies the caller's mapper and applies the required settings on top. The caller's instance is
     * never modified.
     */
    public static ObjectMapper copyOf(ObjectMapper callerMapper) {
        Objects.requireNonNull(callerMapper, "callerMapper");
        return apply(callerMapper.copy());
    }

    private static ObjectMapper apply(ObjectMapper mapper) {
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE);
        // Sleeper timestamps are epoch milliseconds, not seconds with nanosecond fractions.
        mapper.disable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS);
        mapper.registerModule(new JavaTimeModule());
        for (LogicalType type : COERCED_SCALARS) {
            mapper.coercionConfigFor(type)
                    .setCoercion(CoercionInputShape.String, CoercionAction.TryConvert);
        }
        return mapper;
    }
}
