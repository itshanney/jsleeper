package dev.brickfolio.jsleeper.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;

/**
 * Current state of a sport, from {@code /state/{sport}}.
 *
 * @param week current week
 * @param leg week of the regular season
 * @param displayWeek week the Sleeper UI shows; can differ from {@code week}
 * @param season current season, such as "2026"
 * @param previousSeason previous season
 * @param leagueSeason active season for leagues; use this, not {@code season}, to list leagues
 * @param leagueCreateSeason season new leagues are created for; flips in December
 * @param seasonType season phase
 * @param seasonTypeRaw the wire value of {@code seasonType}, kept for {@code UNKNOWN}
 * @param seasonStartDate regular season start, may be null
 * @param seasonHasScores undocumented flag seen in live responses, may be null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SportState(
        Integer week,
        Integer leg,
        Integer displayWeek,
        String season,
        String previousSeason,
        String leagueSeason,
        String leagueCreateSeason,
        SeasonType seasonType,
        String seasonTypeRaw,
        LocalDate seasonStartDate,
        Boolean seasonHasScores) {

    /** JSON entry point: derives {@code seasonType} from the raw {@code season_type} string. */
    @JsonCreator
    static SportState fromJson(
            @JsonProperty("week") Integer week,
            @JsonProperty("leg") Integer leg,
            @JsonProperty("display_week") Integer displayWeek,
            @JsonProperty("season") String season,
            @JsonProperty("previous_season") String previousSeason,
            @JsonProperty("league_season") String leagueSeason,
            @JsonProperty("league_create_season") String leagueCreateSeason,
            @JsonProperty("season_type") String seasonTypeRaw,
            @JsonProperty("season_start_date") LocalDate seasonStartDate,
            @JsonProperty("season_has_scores") Boolean seasonHasScores) {
        return new SportState(week, leg, displayWeek, season, previousSeason, leagueSeason,
                leagueCreateSeason, SeasonType.fromWire(seasonTypeRaw), seasonTypeRaw,
                seasonStartDate, seasonHasScores);
    }
}
