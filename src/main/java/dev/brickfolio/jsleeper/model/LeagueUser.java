package dev.brickfolio.jsleeper.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.Optional;

/**
 * A member of a league. {@code metadata} is never null.
 *
 * @param userId user ID
 * @param username login name; usually absent in league member rows
 * @param displayName name shown in the app
 * @param avatar avatar ID, may be null
 * @param isOwner true for a commissioner (there can be several); null when Sleeper omits it
 * @param metadata free-form metadata, which sometimes includes {@code team_name}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LeagueUser(
        String userId,
        String username,
        String displayName,
        String avatar,
        @JsonProperty("is_owner") Boolean isOwner,
        Map<String, Object> metadata) {

    private static final String TEAM_NAME_KEY = "team_name";

    /** Normalises null metadata to an empty immutable map. */
    public LeagueUser {
        metadata = Immutables.map(metadata);
    }

    /**
     * Returns the team name the user gave their team, if set and non-blank.
     *
     * @return the team name, or empty
     */
    public Optional<String> teamName() {
        return Optional.ofNullable(metadata.get(TEAM_NAME_KEY))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(name -> !name.isBlank());
    }
}
