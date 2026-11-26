package dev.brickfolio.jsleeper.util;

import dev.brickfolio.jsleeper.internal.UrlEncoding;
import java.net.URI;

/** Builds Sleeper CDN avatar URLs. */
public final class Avatars {

    private static final String FULL_PREFIX = "https://sleepercdn.com/avatars/";
    private static final String THUMB_PREFIX = "https://sleepercdn.com/avatars/thumbs/";

    private Avatars() {}

    /**
     * Returns the full-size image URL.
     *
     * @throws IllegalArgumentException if {@code avatarId} is null or blank
     */
    public static URI full(String avatarId) {
        return URI.create(FULL_PREFIX + encode(avatarId));
    }

    /**
     * Returns the thumbnail URL.
     *
     * @throws IllegalArgumentException if {@code avatarId} is null or blank
     */
    public static URI thumb(String avatarId) {
        return URI.create(THUMB_PREFIX + encode(avatarId));
    }

    private static String encode(String avatarId) {
        if (avatarId == null || avatarId.isBlank()) {
            throw new IllegalArgumentException("avatarId must not be blank");
        }
        return UrlEncoding.encode(avatarId);
    }
}
