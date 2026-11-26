package dev.brickfolio.jsleeper.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RosterSettingsTest {

    private static RosterSettings withPoints(Integer fpts, Integer fptsDecimal) {
        return new RosterSettings(null, null, null, null, null, null, fpts, fptsDecimal,
                null, null, null, null, null);
    }

    @Test
    void points_for_combines_whole_and_hundredths() {
        assertThat(withPoints(1617, 78).pointsFor()).isEqualTo(new BigDecimal("1617.78"));
    }

    @Test
    void null_decimal_is_treated_as_zero_with_scale_two() {
        BigDecimal result = withPoints(1617, null).pointsFor();

        assertThat(result).isEqualTo(new BigDecimal("1617.00"));
        assertThat(result.scale()).isEqualTo(2);
    }

    @Test
    void five_hundredths_is_point_zero_five_not_point_five() {
        assertThat(withPoints(0, 5).pointsFor()).isEqualTo(new BigDecimal("0.05"));
    }

    @Test
    void both_parts_null_give_zero_with_scale_two() {
        assertThat(withPoints(null, null).pointsFor()).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void decimal_without_whole_part_still_counts() {
        assertThat(withPoints(null, 99).pointsFor()).isEqualTo(new BigDecimal("0.99"));
    }

    @Test
    void points_against_and_potential_points_use_their_own_fields() {
        RosterSettings s = new RosterSettings(null, null, null, null, null, null, 1, 1,
                1670, 32, null, 1437, 26);

        assertThat(s.pointsAgainst()).isEqualTo(new BigDecimal("1670.32"));
        assertThat(s.potentialPoints()).isEqualTo(new BigDecimal("1437.26"));
        assertThat(s.pointsFor()).isEqualTo(new BigDecimal("1.01"));
    }
}
