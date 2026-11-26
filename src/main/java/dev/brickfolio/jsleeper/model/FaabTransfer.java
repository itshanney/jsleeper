package dev.brickfolio.jsleeper.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * FAAB dollars sent from one roster to another as part of a trade.
 *
 * @param sender roster sending the budget
 * @param receiver roster receiving it
 * @param amount dollars
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FaabTransfer(int sender, int receiver, int amount) {}
