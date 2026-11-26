package dev.brickfolio.jsleeper.api;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import dev.brickfolio.jsleeper.internal.EmptyKind;
import dev.brickfolio.jsleeper.internal.Requester;
import dev.brickfolio.jsleeper.model.Sport;
import dev.brickfolio.jsleeper.model.SportState;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Sport state endpoint ({@code /state/{sport}}). Stateless and thread-safe. */
public final class StateApi {

    private static final JavaType SPORT_STATE_TYPE =
            TypeFactory.defaultInstance().constructType(SportState.class);

    private final Requester requester;

    /** For use by {@link dev.brickfolio.jsleeper.SleeperClient} only. */
    @SuppressWarnings("exports")
    public StateApi(Requester requester) {
        this.requester = Objects.requireNonNull(requester, "requester");
    }

    /**
     * Returns the current state of a sport.
     *
     * @throws IllegalArgumentException if {@code sport} is {@link Sport#UNKNOWN}
     * @throws dev.brickfolio.jsleeper.error.SleeperApiException if Sleeper has no state for the sport
     */
    public SportState get(Sport sport) {
        Objects.requireNonNull(sport, "sport");
        if (sport == Sport.UNKNOWN) {
            throw new IllegalArgumentException("sport must not be UNKNOWN");
        }
        return requester.get(List.of("state", sport.wireValue()), Map.of(), SPORT_STATE_TYPE,
                EmptyKind.REQUIRED, null);
    }
}
