package dev.brickfolio.jsleeper.api;

import java.util.LinkedHashMap;
import java.util.Map;

/** Filters for {@link PlayersApi#all(dev.brickfolio.jsleeper.model.Sport, PlayerQuery)}. */
public final class PlayerQuery {

    private final String position;
    private final boolean activeOnly;

    private PlayerQuery(String position, boolean activeOnly) {
        this.position = position;
        this.activeOnly = activeOnly;
    }

    /** Returns a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns true if no filter is set, so the call returns the full ~5 MB map. */
    boolean isUnfiltered() {
        return position == null && !activeOnly;
    }

    /** Query parameters to send; {@code active=true} only when requested. */
    Map<String, String> toQueryParameters() {
        Map<String, String> params = new LinkedHashMap<>();
        if (position != null) {
            params.put("position", position);
        }
        if (activeOnly) {
            params.put("active", "true");
        }
        return params;
    }

    /** Builder for {@link PlayerQuery}. */
    public static final class Builder {
        private String position;
        private boolean activeOnly;

        private Builder() {}

        /** Only players whose fantasy positions include this one, such as "QB". Must not be blank. */
        public Builder position(String position) {
            if (position == null || position.isBlank()) {
                throw new IllegalArgumentException("position must not be blank");
            }
            this.position = position;
            return this;
        }

        /** Only active players. Sleeper ignores any other value, so only {@code true} is modelled. */
        public Builder activeOnly(boolean activeOnly) {
            this.activeOnly = activeOnly;
            return this;
        }

        /** Builds the query. */
        public PlayerQuery build() {
            return new PlayerQuery(position, activeOnly);
        }
    }
}
