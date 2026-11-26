package dev.brickfolio.jsleeper.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A draft. Collections are never null; {@code settings} and {@code metadata} are never null.
 *
 * @param draftId draft ID
 * @param leagueId league ID
 * @param type draft format
 * @param typeRaw wire value of {@code type}
 * @param status draft state
 * @param statusRaw wire value of {@code status}
 * @param sport sport
 * @param sportRaw wire value of {@code sport}
 * @param season season such as "2026"
 * @param seasonType season phase
 * @param seasonTypeRaw wire value of {@code seasonType}
 * @param startTime scheduled start, may be null
 * @param created creation time, may be null
 * @param lastPicked time of the latest pick, may be null
 * @param lastMessageTime time of the latest chat message, may be null
 * @param lastMessageId latest chat message ID, may be null
 * @param settings draft settings
 * @param metadata draft metadata
 * @param draftOrder user ID to draft slot; empty when not yet set
 * @param slotToRosterId draft slot to roster ID; only filled by the single-draft endpoint, empty
 *     in list responses
 * @param creators user IDs that created the draft
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Draft(
        String draftId,
        String leagueId,
        DraftType type,
        String typeRaw,
        DraftStatus status,
        String statusRaw,
        Sport sport,
        String sportRaw,
        String season,
        SeasonType seasonType,
        String seasonTypeRaw,
        Instant startTime,
        Instant created,
        Instant lastPicked,
        Instant lastMessageTime,
        String lastMessageId,
        DraftSettings settings,
        DraftMetadata metadata,
        Map<String, Integer> draftOrder,
        Map<Integer, Integer> slotToRosterId,
        List<String> creators) {

    /** Normalises nulls to empty values. */
    public Draft {
        settings = settings == null ? DraftSettings.empty() : settings;
        metadata = metadata == null ? new DraftMetadata(null, null, null) : metadata;
        draftOrder = Immutables.map(draftOrder);
        slotToRosterId = Immutables.map(slotToRosterId);
        creators = Immutables.list(creators);
    }

    /** JSON entry point: derives each enum from its raw wire string. */
    @JsonCreator
    static Draft fromJson(
            @JsonProperty("draft_id") String draftId,
            @JsonProperty("league_id") String leagueId,
            @JsonProperty("type") String typeRaw,
            @JsonProperty("status") String statusRaw,
            @JsonProperty("sport") String sportRaw,
            @JsonProperty("season") String season,
            @JsonProperty("season_type") String seasonTypeRaw,
            @JsonProperty("start_time") Instant startTime,
            @JsonProperty("created") Instant created,
            @JsonProperty("last_picked") Instant lastPicked,
            @JsonProperty("last_message_time") Instant lastMessageTime,
            @JsonProperty("last_message_id") String lastMessageId,
            @JsonProperty("settings") DraftSettings settings,
            @JsonProperty("metadata") DraftMetadata metadata,
            @JsonProperty("draft_order") Map<String, Integer> draftOrder,
            @JsonProperty("slot_to_roster_id") Map<Integer, Integer> slotToRosterId,
            @JsonProperty("creators") List<String> creators) {
        return new Draft(draftId, leagueId,
                DraftType.fromWire(typeRaw), typeRaw,
                DraftStatus.fromWire(statusRaw), statusRaw,
                Sport.fromWire(sportRaw), sportRaw,
                season, SeasonType.fromWire(seasonTypeRaw), seasonTypeRaw,
                startTime, created, lastPicked, lastMessageTime, lastMessageId,
                settings, metadata, draftOrder, slotToRosterId, creators);
    }
}
