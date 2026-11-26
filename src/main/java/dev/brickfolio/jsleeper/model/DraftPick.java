package dev.brickfolio.jsleeper.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One pick of a draft. {@code metadata} is never null.
 *
 * @param draftId draft ID
 * @param pickNo overall pick number
 * @param round round; null if Sleeper omits it
 * @param draftSlot draft board column; null if Sleeper omits it
 * @param rosterId roster that receives the pick (coerced from Sleeper's string); null if omitted
 * @param playerId drafted player ID
 * @param pickedBy user ID the pick goes to; null when Sleeper sends a blank string (a slot with no user)
 * @param isKeeper whether the pick is a keeper; usually null
 * @param metadata drafted player's details
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DraftPick(
        String draftId,
        int pickNo,
        Integer round,
        Integer draftSlot,
        Integer rosterId,
        String playerId,
        String pickedBy,
        @JsonProperty("is_keeper") Boolean isKeeper,
        DraftPickMetadata metadata) {

    /** Turns a blank {@code pickedBy} into null and null metadata into an all-null instance. */
    public DraftPick {
        pickedBy = pickedBy == null || pickedBy.isBlank() ? null : pickedBy;
        metadata = metadata == null ? DraftPickMetadata.empty() : metadata;
    }
}
