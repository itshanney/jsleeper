package dev.brickfolio.jsleeper.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class UnfilteredFetchWatchTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final List<String> warnings = new ArrayList<>();
    private final UnfilteredFetchWatch watch = new UnfilteredFetchWatch(clock, warnings::add);

    @Test
    void the_first_fetch_does_not_warn() {
        watch.onUnfilteredFetch();

        assertThat(warnings).isEmpty();
    }

    @Test
    void a_second_fetch_within_an_hour_warns_once_and_points_to_player_directory() {
        watch.onUnfilteredFetch();
        clock.advance(Duration.ofMinutes(30));

        watch.onUnfilteredFetch();

        assertThat(warnings).singleElement().asString().contains("PlayerDirectory").contains("per day");
    }

    @Test
    void further_fast_fetches_do_not_warn_again_for_the_life_of_the_client() {
        watch.onUnfilteredFetch();
        watch.onUnfilteredFetch();
        watch.onUnfilteredFetch();
        clock.advance(Duration.ofMinutes(10));
        watch.onUnfilteredFetch();

        assertThat(warnings).hasSize(1);
    }

    @Test
    void fetches_at_least_an_hour_apart_do_not_warn() {
        watch.onUnfilteredFetch();
        clock.advance(Duration.ofHours(1));
        watch.onUnfilteredFetch();
        clock.advance(Duration.ofHours(25));
        watch.onUnfilteredFetch();

        assertThat(warnings).isEmpty();
    }

    @Test
    void the_interval_is_measured_from_the_latest_fetch() {
        watch.onUnfilteredFetch();
        clock.advance(Duration.ofMinutes(59));
        watch.onUnfilteredFetch();

        assertThat(warnings).hasSize(1);
    }
}
