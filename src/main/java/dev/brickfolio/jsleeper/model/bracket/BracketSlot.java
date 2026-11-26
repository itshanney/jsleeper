package dev.brickfolio.jsleeper.model.bracket;

/**
 * One side of a bracket match: a known team, or where the team will come from.
 *
 * <pre>{@code
 * String text = switch (slot) {
 *     case BracketSlot.Team(int rosterId)  -> "Roster " + rosterId;
 *     case BracketSlot.WinnerOf(int match) -> "Winner of M" + match;
 *     case BracketSlot.LoserOf(int match)  -> "Loser of M" + match;
 *     case BracketSlot.Tbd()               -> "TBD";
 * };
 * }</pre>
 */
public sealed interface BracketSlot {

    /**
     * A known team.
     *
     * @param rosterId the team's roster ID
     */
    record Team(int rosterId) implements BracketSlot {}

    /**
     * The winner of another match.
     *
     * @param matchId the feeding match's ID
     */
    record WinnerOf(int matchId) implements BracketSlot {}

    /**
     * The loser of another match.
     *
     * @param matchId the feeding match's ID
     */
    record LoserOf(int matchId) implements BracketSlot {}

    /** Not yet determined, with no known source. */
    record Tbd() implements BracketSlot {}
}
