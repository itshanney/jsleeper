package dev.brickfolio.jsleeper.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A draft pick that changed hands.
 *
 * @param season season the pick belongs to, such as "2027"
 * @param round round of the pick
 * @param rosterId roster that originally owned the pick
 * @param previousOwnerId roster that owned it before this trade
 * @param ownerId roster that owns it now
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TradedPick(
        String season,
        int round,
        Integer rosterId,
        Integer previousOwnerId,
        Integer ownerId) {}
