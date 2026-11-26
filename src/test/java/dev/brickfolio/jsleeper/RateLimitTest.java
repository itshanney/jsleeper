package dev.brickfolio.jsleeper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RateLimitTest {

    @Test
    void defaults_are_900_per_minute_burst_50_and_30_second_max_wait() {
        RateLimit d = RateLimit.defaults();

        assertThat(d.perMinute()).isEqualTo(900);
        assertThat(d.burst()).isEqualTo(50);
        assertThat(d.maxWait()).isEqualTo(Duration.ofSeconds(30));
        assertThat(d.isDisabled()).isFalse();
    }

    @Test
    void per_minute_chains_burst_and_max_wait_into_new_instances() {
        RateLimit base = RateLimit.perMinute(600);

        RateLimit tuned = base.burst(20).maxWait(Duration.ofSeconds(10));

        assertThat(tuned.perMinute()).isEqualTo(600);
        assertThat(tuned.burst()).isEqualTo(20);
        assertThat(tuned.maxWait()).isEqualTo(Duration.ofSeconds(10));
        assertThat(base.burst()).isEqualTo(50);
        assertThat(base.maxWait()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void default_burst_never_exceeds_the_per_minute_rate() {
        assertThat(RateLimit.perMinute(10).burst()).isEqualTo(10);
        assertThat(RateLimit.perMinute(1000).burst()).isEqualTo(50);
    }

    @Test
    void disabled_reports_disabled() {
        assertThat(RateLimit.disabled().isDisabled()).isTrue();
    }

    @Test
    void zero_max_wait_is_allowed() {
        assertThat(RateLimit.perMinute(1).maxWait(Duration.ZERO).maxWait()).isEqualTo(Duration.ZERO);
    }

    @Test
    void invalid_values_are_rejected() {
        assertThatThrownBy(() -> RateLimit.perMinute(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RateLimit.perMinute(-5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RateLimit.perMinute(10).burst(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RateLimit.perMinute(10).maxWait(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RateLimit.perMinute(10).maxWait(null)).isInstanceOf(NullPointerException.class);
    }
}
