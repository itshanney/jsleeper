package dev.brickfolio.jsleeper.util;

import dev.brickfolio.jsleeper.model.Matchup;
import dev.brickfolio.jsleeper.model.MatchupPair;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Groups a week's matchup rows into head-to-head pairs. */
public final class Matchups {

    private static final int TEAMS_PER_MATCHUP = 2;

    private Matchups() {}

    /**
     * Pairs rows that share a {@code matchupId}, in first-seen order; the first row is home and
     * the second is away. A row with a null {@code matchupId} becomes its own pair with a null
     * away side.
     *
     * @return the matchup pairs, or an empty list if there are none
     * @param rows the matchup rows of one week
     * @throws NullPointerException if {@code rows} is null
     * @throws IllegalArgumentException if more than two rows share a {@code matchupId}
     */
    public static List<MatchupPair> pair(List<Matchup> rows) {
        Objects.requireNonNull(rows, "rows");
        // Pairs are built in encounter order; a LinkedHashMap keyed by matchupId finds the open one.
        List<MatchupPair> pairs = new ArrayList<>();
        Map<Integer, Integer> indexByMatchupId = new LinkedHashMap<>();
        Map<Integer, Integer> sizeByMatchupId = new LinkedHashMap<>();
        for (Matchup row : rows) {
            Integer id = row.matchupId();
            if (id == null) {
                pairs.add(new MatchupPair(null, row, null));
                continue;
            }
            int size = sizeByMatchupId.merge(id, 1, Integer::sum);
            if (size > TEAMS_PER_MATCHUP) {
                throw new IllegalArgumentException(
                        "matchupId " + id + " has more than " + TEAMS_PER_MATCHUP + " rows");
            }
            Integer index = indexByMatchupId.get(id);
            if (index == null) {
                indexByMatchupId.put(id, pairs.size());
                pairs.add(new MatchupPair(id, row, null));
            } else {
                MatchupPair first = pairs.get(index);
                pairs.set(index, new MatchupPair(id, first.home(), row));
            }
        }
        return List.copyOf(pairs);
    }
}
