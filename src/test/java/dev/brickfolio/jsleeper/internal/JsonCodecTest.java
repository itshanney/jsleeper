package dev.brickfolio.jsleeper.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.brickfolio.jsleeper.model.WireEnum;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class JsonCodecTest {

    enum Phase implements WireEnum {
        @JsonProperty("pre_draft") PRE_DRAFT("pre_draft"),
        @JsonProperty("in_season") IN_SEASON("in_season"),
        @JsonEnumDefaultValue UNKNOWN("unknown");

        private final String wire;

        Phase(String wire) {
            this.wire = wire;
        }

        @Override
        public String wireValue() {
            return wire;
        }
    }

    record Sample(String displayName, Integer totalRosters, Phase status, Instant statusUpdated,
            LocalDate seasonStartDate, Double points, Boolean isOwner) {}

    private final ObjectMapper mapper = JsonCodec.create();

    private Sample read(String json) throws JsonProcessingException {
        return mapper.readValue(json, Sample.class);
    }

    @Test
    void snake_case_names_map_to_camel_case_record_components() throws Exception {
        Sample s = read("{\"display_name\":\"Ann\",\"total_rosters\":12,\"is_owner\":true}");

        assertThat(s.displayName()).isEqualTo("Ann");
        assertThat(s.totalRosters()).isEqualTo(12);
        assertThat(s.isOwner()).isTrue();
    }

    @Test
    void unknown_properties_are_ignored() throws Exception {
        Sample s = read("{\"display_name\":\"Ann\",\"brand_new_field\":{\"x\":1}}");

        assertThat(s.displayName()).isEqualTo("Ann");
    }

    @Test
    void known_enum_value_maps_by_wire_name() throws Exception {
        assertThat(read("{\"status\":\"in_season\"}").status()).isEqualTo(Phase.IN_SEASON);
    }

    @Test
    void unknown_enum_value_maps_to_unknown_constant() throws Exception {
        assertThat(read("{\"status\":\"some_future_status\"}").status()).isEqualTo(Phase.UNKNOWN);
    }

    @Test
    void epoch_millis_map_to_instant_with_exact_millis() throws Exception {
        Sample s = read("{\"status_updated\":1558039402803}");

        assertThat(s.statusUpdated()).isEqualTo(Instant.ofEpochMilli(1558039402803L));
    }

    @Test
    void numeric_string_is_coerced_to_integer() throws Exception {
        assertThat(read("{\"total_rosters\":\"10\"}").totalRosters()).isEqualTo(10);
    }

    @Test
    void numeric_string_is_coerced_to_double() throws Exception {
        assertThat(read("{\"points\":\"20.5\"}").points()).isEqualTo(20.5);
    }

    @Test
    void iso_date_string_maps_to_local_date() throws Exception {
        assertThat(read("{\"season_start_date\":\"2026-09-09\"}").seasonStartDate())
                .isEqualTo(LocalDate.of(2026, 9, 9));
    }

    @Test
    void null_values_stay_null() throws Exception {
        Sample s = read("{\"total_rosters\":null,\"status_updated\":null}");

        assertThat(s.totalRosters()).isNull();
        assertThat(s.statusUpdated()).isNull();
    }

    @Test
    void non_numeric_string_for_integer_still_fails() {
        assertThatThrownBy(() -> read("{\"total_rosters\":\"abc\"}"))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void copy_of_leaves_callers_mapper_unchanged() {
        ObjectMapper caller = new ObjectMapper();

        ObjectMapper copy = JsonCodec.copyOf(caller);

        assertThat(copy).isNotSameAs(caller);
        assertThat(caller.getPropertyNamingStrategy()).isNull();
        assertThat(caller.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
        assertThat(caller.isEnabled(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE))
                .isFalse();
        assertThat(caller.isEnabled(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS)).isTrue();
    }

    @Test
    void copy_of_applies_required_settings_over_callers_conflicting_ones() throws Exception {
        ObjectMapper caller = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        Sample s = JsonCodec.copyOf(caller).readValue(
                "{\"display_name\":\"Ann\",\"extra\":1,\"status_updated\":1558039402803}", Sample.class);

        assertThat(s.displayName()).isEqualTo("Ann");
        assertThat(s.statusUpdated()).isEqualTo(Instant.ofEpochMilli(1558039402803L));
    }

    @Test
    void copy_of_keeps_callers_unrelated_settings() throws Exception {
        ObjectMapper caller = new ObjectMapper()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

        assertThat(JsonCodec.copyOf(caller).isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS))
                .isTrue();
    }
}
