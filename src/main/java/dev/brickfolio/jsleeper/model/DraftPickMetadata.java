package dev.brickfolio.jsleeper.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Player details attached to a draft pick. Sleeper sends every value as a string, and numbers are
 * coerced to strings here. Other keys are ignored.
 *
 * @param firstName player's first name
 * @param lastName player's last name
 * @param position position such as "RB"
 * @param team NFL team code
 * @param number jersey number
 * @param status player status such as "Active"
 * @param injuryStatus injury status, often empty
 * @param sport sport
 * @param playerId player ID
 * @param newsUpdated epoch milliseconds of the latest news, as a string
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DraftPickMetadata(
        String firstName,
        String lastName,
        String position,
        String team,
        String number,
        String status,
        String injuryStatus,
        String sport,
        String playerId,
        String newsUpdated) {

    /** Metadata with every field null; used when Sleeper sends none. */
    static DraftPickMetadata empty() {
        return new DraftPickMetadata(null, null, null, null, null, null, null, null, null, null);
    }
}
