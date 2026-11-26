package dev.brickfolio.jsleeper.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A Sleeper user. Every field except {@code userId} may be null; for example league member rows
 * carry no username. Store {@code userId}, not {@code username}: usernames can change.
 *
 * @param userId stable numeric ID, as a string
 * @param username login name, may be null
 * @param displayName name shown in the app
 * @param avatar avatar ID for {@link dev.brickfolio.jsleeper.util.Avatars}, may be null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record User(String userId, String username, String displayName, String avatar) {}
