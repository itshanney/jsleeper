package dev.brickfolio.jsleeper.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A team in a league. Lists are never null and {@code settings} is never null.
 *
 * @param rosterId roster ID, scoped to the league
 * @param leagueId league ID
 * @param ownerId owner's user ID; null for an orphaned team
 * @param players all player IDs on the roster (team defenses use codes such as "DET")
 * @param starters starter player IDs in lineup-slot order
 * @param reserve injured-reserve player IDs
 * @param taxi taxi-squad player IDs
 * @param coOwners user IDs of co-owners
 * @param settings record and points; all fields null if Sleeper sent none
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Roster(
        int rosterId,
        String leagueId,
        String ownerId,
        List<String> players,
        List<String> starters,
        List<String> reserve,
        List<String> taxi,
        List<String> coOwners,
        RosterSettings settings) {

    /** Normalises null lists to empty immutable ones and null settings to an all-null instance. */
    public Roster {
        players = Immutables.list(players);
        starters = Immutables.list(starters);
        reserve = Immutables.list(reserve);
        taxi = Immutables.list(taxi);
        coOwners = Immutables.list(coOwners);
        settings = settings == null ? RosterSettings.empty() : settings;
    }
}
