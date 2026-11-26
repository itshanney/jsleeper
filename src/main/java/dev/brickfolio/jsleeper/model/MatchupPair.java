package dev.brickfolio.jsleeper.model;

/**
 * Two teams facing each other in a week.
 *
 * @param matchupId the shared matchup ID; null for a single-team row
 * @param home the first row seen for this matchup
 * @param away the opponent; null on a bye or median-only row
 */
public record MatchupPair(Integer matchupId, Matchup home, Matchup away) {}
