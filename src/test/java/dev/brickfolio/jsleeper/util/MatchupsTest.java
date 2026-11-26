package dev.brickfolio.jsleeper.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.brickfolio.jsleeper.model.Matchup;
import dev.brickfolio.jsleeper.model.MatchupPair;
import java.util.List;
import org.junit.jupiter.api.Test;

class MatchupsTest {

    private static Matchup row(int rosterId, Integer matchupId) {
        return new Matchup(rosterId, matchupId, 1.0, null, null, null, null, null);
    }

    @Test
    void rows_with_same_matchup_id_pair_up_in_first_seen_order() {
        List<MatchupPair> pairs = Matchups.pair(List.of(row(1, 6), row(2, 3), row(3, 6), row(4, 3)));

        assertThat(pairs).extracting(MatchupPair::matchupId).containsExactly(6, 3);
        assertThat(pairs.get(0).home().rosterId()).isEqualTo(1);
        assertThat(pairs.get(0).away().rosterId()).isEqualTo(3);
        assertThat(pairs.get(1).home().rosterId()).isEqualTo(2);
        assertThat(pairs.get(1).away().rosterId()).isEqualTo(4);
    }

    @Test
    void row_with_null_matchup_id_is_its_own_pair_without_away() {
        List<MatchupPair> pairs = Matchups.pair(List.of(row(1, null), row(2, null), row(3, 1), row(4, 1)));

        assertThat(pairs).hasSize(3);
        assertThat(pairs.get(0)).isEqualTo(new MatchupPair(null, row(1, null), null));
        assertThat(pairs.get(1).away()).isNull();
        assertThat(pairs.get(2).away()).isNotNull();
    }

    @Test
    void lone_row_with_a_matchup_id_has_no_away() {
        List<MatchupPair> pairs = Matchups.pair(List.of(row(1, 9)));

        assertThat(pairs).singleElement().satisfies(p -> {
            assertThat(p.matchupId()).isEqualTo(9);
            assertThat(p.away()).isNull();
        });
    }

    @Test
    void three_rows_with_one_matchup_id_throw_naming_the_id() {
        List<Matchup> rows = List.of(row(1, 7), row(2, 7), row(3, 7));

        assertThatThrownBy(() -> Matchups.pair(rows))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("7");
    }

    @Test
    void empty_list_gives_empty_result() {
        assertThat(Matchups.pair(List.of())).isEmpty();
    }

    @Test
    void null_list_throws_npe() {
        assertThatThrownBy(() -> Matchups.pair(null)).isInstanceOf(NullPointerException.class);
    }
}
