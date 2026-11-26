package dev.brickfolio.jsleeper.model.bracket;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * One match of a playoff bracket.
 *
 * <p>{@code team1} and {@code team2} are never null. When a roster ID is known it wins over the
 * source, but {@code team1From} and {@code team2From} still hold where the team came from (a
 * {@link BracketSlot.WinnerOf} or {@link BracketSlot.LoserOf}), which is what draws bracket
 * progression. They are null when Sleeper sends no source.
 *
 * @param round round number, starting at 1
 * @param matchId match ID, unique within the bracket
 * @param team1 first side
 * @param team2 second side
 * @param team1From source of {@code team1}, or null
 * @param team2From source of {@code team2}, or null
 * @param winner winning roster ID once played, else null
 * @param loser losing roster ID once played, else null
 * @param placement the place this match decides (undocumented field {@code p}), or null
 */
@JsonDeserialize(using = BracketMatchDeserializer.class)
public record BracketMatch(
        int round,
        int matchId,
        BracketSlot team1,
        BracketSlot team2,
        BracketSlot team1From,
        BracketSlot team2From,
        Integer winner,
        Integer loser,
        Integer placement) {}
