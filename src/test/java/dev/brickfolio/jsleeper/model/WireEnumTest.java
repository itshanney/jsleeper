package dev.brickfolio.jsleeper.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WireEnumTest {

    @Test
    void from_wire_finds_known_values_by_wire_string() {
        assertThat(Sport.fromWire("nfl")).isEqualTo(Sport.NFL);
        assertThat(SeasonType.fromWire("off")).isEqualTo(SeasonType.OFF);
    }

    @Test
    void from_wire_returns_unknown_for_null_unrecognised_and_wrong_case() {
        assertThat(Sport.fromWire(null)).isEqualTo(Sport.UNKNOWN);
        assertThat(Sport.fromWire("hockey")).isEqualTo(Sport.UNKNOWN);
        assertThat(Sport.fromWire("NFL")).isEqualTo(Sport.UNKNOWN);
    }

    @Test
    void wire_values_match_the_spec() {
        assertThat(Sport.NFL.wireValue()).isEqualTo("nfl");
        assertThat(Sport.NBA.wireValue()).isEqualTo("nba");
        assertThat(Sport.LCS.wireValue()).isEqualTo("lcs");
        assertThat(SeasonType.REGULAR.wireValue()).isEqualTo("regular");
    }
}
