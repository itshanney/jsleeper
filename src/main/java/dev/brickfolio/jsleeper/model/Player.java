package dev.brickfolio.jsleeper.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * An NFL player or team defense. Team defenses have IDs such as "DET" and carry the city in
 * {@code firstName} and the team name in {@code lastName}. Most fields may be null.
 *
 * @param playerId player ID; not numeric for team defenses
 * @param firstName first name (or city for a defense)
 * @param lastName last name (or team name for a defense)
 * @param fullName {@code full_name} if Sleeper sends it, else first and last name joined, else
 *     the player ID
 * @param position primary position
 * @param fantasyPositions positions the player is eligible at; never null
 * @param team team code; null for a free agent
 * @param number jersey number
 * @param status status such as "Active"
 * @param injuryStatus injury status, may be null
 * @param injuryStartDate raw injury start date string, may be null
 * @param practiceParticipation practice participation, may be null
 * @param age age in years
 * @param height height as sent, such as "6'4\""
 * @param weight weight as sent, such as "220"
 * @param college college
 * @param yearsExp years of experience
 * @param depthChartPosition depth chart position as a string; may be a code in real data
 * @param depthChartOrder depth chart order
 * @param searchRank Sleeper's search rank
 * @param birthCountry birth country
 * @param sport sport code
 * @param hashtag hashtag
 * @param active whether the player is active, if sent
 * @param externalIds IDs on other sites
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Player(
        String playerId,
        String firstName,
        String lastName,
        String fullName,
        String position,
        List<String> fantasyPositions,
        String team,
        Integer number,
        String status,
        String injuryStatus,
        String injuryStartDate,
        String practiceParticipation,
        Integer age,
        String height,
        String weight,
        String college,
        Integer yearsExp,
        String depthChartPosition,
        Integer depthChartOrder,
        Integer searchRank,
        String birthCountry,
        String sport,
        String hashtag,
        Boolean active,
        ExternalIds externalIds) {

    /** Derives {@code fullName} when absent and normalises null collections and external IDs. */
    public Player {
        fantasyPositions = Immutables.list(fantasyPositions);
        externalIds = externalIds == null
                ? new ExternalIds(null, null, null, null, null, null, null, null, null) : externalIds;
        if (fullName == null || fullName.isBlank()) {
            fullName = derivedName(firstName, lastName, playerId);
        }
    }

    private static String derivedName(String first, String last, String playerId) {
        String joined = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return joined.isEmpty() ? playerId : joined;
    }

    /** JSON entry point: gathers the flat external-ID fields into {@link ExternalIds}. */
    @JsonCreator
    static Player fromJson(
            @JsonProperty("player_id") String playerId,
            @JsonProperty("first_name") String firstName,
            @JsonProperty("last_name") String lastName,
            @JsonProperty("full_name") String fullName,
            @JsonProperty("position") String position,
            @JsonProperty("fantasy_positions") List<String> fantasyPositions,
            @JsonProperty("team") String team,
            @JsonProperty("number") Integer number,
            @JsonProperty("status") String status,
            @JsonProperty("injury_status") String injuryStatus,
            @JsonProperty("injury_start_date") String injuryStartDate,
            @JsonProperty("practice_participation") String practiceParticipation,
            @JsonProperty("age") Integer age,
            @JsonProperty("height") String height,
            @JsonProperty("weight") String weight,
            @JsonProperty("college") String college,
            @JsonProperty("years_exp") Integer yearsExp,
            @JsonProperty("depth_chart_position") String depthChartPosition,
            @JsonProperty("depth_chart_order") Integer depthChartOrder,
            @JsonProperty("search_rank") Integer searchRank,
            @JsonProperty("birth_country") String birthCountry,
            @JsonProperty("sport") String sport,
            @JsonProperty("hashtag") String hashtag,
            @JsonProperty("active") Boolean active,
            @JsonProperty("espn_id") String espnId,
            @JsonProperty("yahoo_id") String yahooId,
            @JsonProperty("sportradar_id") String sportradarId,
            @JsonProperty("stats_id") String statsId,
            @JsonProperty("fantasy_data_id") String fantasyDataId,
            @JsonProperty("rotowire_id") String rotowireId,
            @JsonProperty("rotoworld_id") String rotoworldId,
            @JsonProperty("gsis_id") String gsisId,
            @JsonProperty("swish_id") String swishId) {
        return new Player(playerId, firstName, lastName, fullName, position, fantasyPositions, team,
                number, status, injuryStatus, injuryStartDate, practiceParticipation, age, height,
                weight, college, yearsExp, depthChartPosition, depthChartOrder, searchRank,
                birthCountry, sport, hashtag, active,
                new ExternalIds(espnId, yahooId, sportradarId, statsId, fantasyDataId, rotowireId,
                        rotoworldId, gsisId, swishId));
    }
}
