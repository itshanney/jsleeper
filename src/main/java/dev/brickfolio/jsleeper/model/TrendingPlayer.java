package dev.brickfolio.jsleeper.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A trending player. The ID can be a team code such as "WAS" for a defense.
 *
 * @param playerId player ID
 * @param count number of adds or drops in the lookback window
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrendingPlayer(String playerId, int count) {}
