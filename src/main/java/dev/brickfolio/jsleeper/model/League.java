package dev.brickfolio.jsleeper.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * A fantasy league. Collections are never null. {@code settings} and {@code scoringSettings} are
 * loosely typed because Sleeper does not document their contents.
 *
 * @param leagueId league ID
 * @param name league name
 * @param season season as a string, such as "2026"
 * @param seasonType season phase
 * @param seasonTypeRaw wire value of {@code seasonType}
 * @param status league lifecycle state
 * @param statusRaw wire value of {@code status}
 * @param sport sport
 * @param sportRaw wire value of {@code sport}
 * @param totalRosters number of teams, may be null
 * @param draftId ID of the (latest) draft, may be null
 * @param previousLeagueId previous season's league in a dynasty chain, may be null
 * @param avatar avatar ID, may be null
 * @param rosterPositions roster slots such as QB, RB, FLEX, BN
 * @param scoringSettings points per stat
 * @param settings league settings, untyped
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record League(
        String leagueId,
        String name,
        String season,
        SeasonType seasonType,
        String seasonTypeRaw,
        LeagueStatus status,
        String statusRaw,
        Sport sport,
        String sportRaw,
        Integer totalRosters,
        String draftId,
        String previousLeagueId,
        String avatar,
        List<String> rosterPositions,
        Map<String, Double> scoringSettings,
        Map<String, Object> settings) {

    /** Normalises null collections to empty immutable ones. */
    public League {
        rosterPositions = Immutables.list(rosterPositions);
        scoringSettings = Immutables.map(scoringSettings);
        settings = Immutables.map(settings);
    }

    /** JSON entry point: derives each enum from its raw wire string. */
    @JsonCreator
    static League fromJson(
            @JsonProperty("league_id") String leagueId,
            @JsonProperty("name") String name,
            @JsonProperty("season") String season,
            @JsonProperty("season_type") String seasonTypeRaw,
            @JsonProperty("status") String statusRaw,
            @JsonProperty("sport") String sportRaw,
            @JsonProperty("total_rosters") Integer totalRosters,
            @JsonProperty("draft_id") String draftId,
            @JsonProperty("previous_league_id") String previousLeagueId,
            @JsonProperty("avatar") String avatar,
            @JsonProperty("roster_positions") List<String> rosterPositions,
            @JsonProperty("scoring_settings") Map<String, Double> scoringSettings,
            @JsonProperty("settings") Map<String, Object> settings) {
        return new League(leagueId, name, season,
                SeasonType.fromWire(seasonTypeRaw), seasonTypeRaw,
                LeagueStatus.fromWire(statusRaw), statusRaw,
                Sport.fromWire(sportRaw), sportRaw,
                totalRosters, draftId, previousLeagueId, avatar,
                rosterPositions, scoringSettings, settings);
    }
}
