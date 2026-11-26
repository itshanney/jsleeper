package dev.brickfolio.jsleeper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void defaults_match_the_spec() {
        RetryPolicy p = RetryPolicy.defaults();

        assertThat(p.maxAttempts()).isEqualTo(3);
        assertThat(p.initialBackoff()).isEqualTo(Duration.ofMillis(500));
        assertThat(p.maxBackoff()).isEqualTo(Duration.ofSeconds(8));
        assertThat(p.retryOnStatuses()).containsExactlyInAnyOrder(429, 500, 502, 503, 504);
        assertThat(p.retryOnTransportErrors()).isTrue();
    }

    @Test
    void none_is_a_single_attempt() {
        assertThat(RetryPolicy.none().maxAttempts()).isEqualTo(1);
    }

    @Test
    void backoff_ceiling_doubles_then_is_capped() {
        RetryPolicy p = RetryPolicy.defaults();

        assertThat(p.backoffCeiling(1)).isEqualTo(Duration.ofMillis(500));
        assertThat(p.backoffCeiling(2)).isEqualTo(Duration.ofSeconds(1));
        assertThat(p.backoffCeiling(3)).isEqualTo(Duration.ofSeconds(2));
        assertThat(p.backoffCeiling(4)).isEqualTo(Duration.ofSeconds(4));
        assertThat(p.backoffCeiling(5)).isEqualTo(Duration.ofSeconds(8));
        assertThat(p.backoffCeiling(6)).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void backoff_ceiling_does_not_overflow_for_huge_retry_indexes() {
        RetryPolicy p = RetryPolicy.defaults();

        assertThat(p.backoffCeiling(500)).isEqualTo(Duration.ofSeconds(8));
        assertThat(p.backoffCeiling(Integer.MAX_VALUE)).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void backoff_ceiling_rejects_retry_index_below_one() {
        assertThatThrownBy(() -> RetryPolicy.defaults().backoffCeiling(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builder_applies_every_option() {
        RetryPolicy p = RetryPolicy.builder().maxAttempts(5).initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofMillis(100)).retryOn(Set.of(418)).retryOnTransportErrors(false).build();

        assertThat(p.maxAttempts()).isEqualTo(5);
        assertThat(p.initialBackoff()).isEqualTo(Duration.ofMillis(100));
        assertThat(p.retryOnStatuses()).containsExactly(418);
        assertThat(p.retryOnTransportErrors()).isFalse();
    }

    @Test
    void builder_rejects_invalid_values() {
        assertThatThrownBy(() -> RetryPolicy.builder().maxAttempts(0).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetryPolicy.builder().initialBackoff(Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetryPolicy.builder().initialBackoff(Duration.ofMillis(-1)).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetryPolicy.builder().initialBackoff(Duration.ofSeconds(2))
                .maxBackoff(Duration.ofSeconds(1)).build()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builder_accepts_max_backoff_equal_to_initial() {
        assertThat(RetryPolicy.builder().initialBackoff(Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(1)).build()).isNotNull();
    }

    @Test
    void builder_rejects_nulls() {
        assertThatThrownBy(() -> RetryPolicy.builder().retryOn(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RetryPolicy.builder().initialBackoff(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RetryPolicy.builder().maxBackoff(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void status_set_is_immutable() {
        assertThatThrownBy(() -> RetryPolicy.defaults().retryOnStatuses().add(418))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
