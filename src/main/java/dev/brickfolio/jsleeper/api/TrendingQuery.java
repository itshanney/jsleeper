package dev.brickfolio.jsleeper.api;

import java.util.LinkedHashMap;
import java.util.Map;

/** Options for {@link PlayersApi#trending}. Unset options use Sleeper's defaults (24h, limit 25). */
public final class TrendingQuery {

    private final Integer lookbackHours;
    private final Integer limit;

    private TrendingQuery(Integer lookbackHours, Integer limit) {
        this.lookbackHours = lookbackHours;
        this.limit = limit;
    }

    /** Returns a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    Map<String, String> toQueryParameters() {
        Map<String, String> params = new LinkedHashMap<>();
        if (lookbackHours != null) {
            params.put("lookback_hours", lookbackHours.toString());
        }
        if (limit != null) {
            params.put("limit", limit.toString());
        }
        return params;
    }

    /** Builder for {@link TrendingQuery}. */
    public static final class Builder {
        private Integer lookbackHours;
        private Integer limit;

        private Builder() {}

        /** Hours to look back; must be at least 1 (checked in {@link #build()}). */
        public Builder lookbackHours(int lookbackHours) {
            this.lookbackHours = lookbackHours;
            return this;
        }

        /** Maximum results; must be at least 1 (checked in {@link #build()}). */
        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        /**
         * Builds the query.
         *
         * @throws IllegalArgumentException if {@code lookbackHours} or {@code limit} is below 1
         */
        public TrendingQuery build() {
            if (lookbackHours != null && lookbackHours < 1) {
                throw new IllegalArgumentException("lookbackHours must be at least 1: " + lookbackHours);
            }
            if (limit != null && limit < 1) {
                throw new IllegalArgumentException("limit must be at least 1: " + limit);
            }
            return new TrendingQuery(lookbackHours, limit);
        }
    }
}
