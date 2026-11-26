package dev.brickfolio.jsleeper.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One team's side of a weekly matchup. Two rows with the same {@code matchupId} play each other.
 * Collections are never null.
 *
 * @param rosterId roster ID
 * @param matchupId pairing key; null for a bye or median-only row
 * @param points total points under league settings, may be null
 * @param customPoints commissioner override of {@code points}, null when not overridden
 * @param players all player IDs in this matchup
 * @param starters starter player IDs in lineup-slot order
 * @param playersPoints points per player ID
 * @param startersPoints points per starter, in {@code starters} order
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Matchup(
        int rosterId,
        Integer matchupId,
        Double points,
        Double customPoints,
        List<String> players,
        List<String> starters,
        Map<String, Double> playersPoints,
        List<Double> startersPoints) {

    /** Normalises null collections to empty immutable ones. */
    public Matchup {
        players = Immutables.list(players);
        starters = Immutables.list(starters);
        playersPoints = Immutables.map(playersPoints);
        startersPoints = Immutables.list(startersPoints);
    }

    /** Returns {@code customPoints} if set, else {@code points}, else 0.0. */
    public double effectivePoints() {
        if (customPoints != null) {
            return customPoints;
        }
        return points == null ? 0.0 : points;
    }

    /** Returns the players not in the starting lineup, in {@code players} order. */
    public List<String> bench() {
        Set<String> started = new HashSet<>(starters);
        List<String> bench = new ArrayList<>();
        for (String player : players) {
            if (!started.contains(player)) {
                bench.add(player);
            }
        }
        return List.copyOf(bench);
    }
}
