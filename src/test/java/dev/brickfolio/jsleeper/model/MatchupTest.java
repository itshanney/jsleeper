package dev.brickfolio.jsleeper.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MatchupTest {

    private static Matchup of(Double points, Double custom, List<String> players, List<String> starters) {
        return new Matchup(1, 1, points, custom, players, starters, null, null);
    }

    @Test
    void custom_points_override_points() {
        assertThat(of(100.0, 150.5, null, null).effectivePoints()).isEqualTo(150.5);
    }

    @Test
    void zero_custom_points_still_override() {
        assertThat(of(100.0, 0.0, null, null).effectivePoints()).isZero();
    }

    @Test
    void points_are_used_when_no_override() {
        assertThat(of(100.25, null, null, null).effectivePoints()).isEqualTo(100.25);
    }

    @Test
    void both_null_gives_zero() {
        assertThat(of(null, null, null, null).effectivePoints()).isZero();
    }

    @Test
    void bench_is_players_minus_starters_in_players_order() {
        Matchup m = of(0.0, null, List.of("a", "b", "c", "d"), List.of("c", "a"));

        assertThat(m.bench()).containsExactly("b", "d");
    }

    @Test
    void empty_slot_placeholder_starter_does_not_affect_bench() {
        Matchup m = of(0.0, null, List.of("a", "b"), List.of("a", "0"));

        assertThat(m.bench()).containsExactly("b");
    }

    @Test
    void null_collections_become_empty() {
        Matchup m = new Matchup(1, null, null, null, null, null, null, null);

        assertThat(m.players()).isEmpty();
        assertThat(m.starters()).isEmpty();
        assertThat(m.playersPoints()).isEqualTo(Map.of());
        assertThat(m.startersPoints()).isEmpty();
        assertThat(m.bench()).isEmpty();
    }
}
