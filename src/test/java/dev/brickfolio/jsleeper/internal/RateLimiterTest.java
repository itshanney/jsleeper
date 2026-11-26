package dev.brickfolio.jsleeper.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.brickfolio.jsleeper.RateLimit;
import dev.brickfolio.jsleeper.error.SleeperRateLimitException;
import dev.brickfolio.jsleeper.error.SleeperTransportException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Token-bucket math against a fake clock; nothing here really sleeps except one bounded test. */
class RateLimiterTest {

    private static final long INTERVAL_900_PER_MIN_NANOS = 66_666_667L;  // 60s / 900, rounded up

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final List<Duration> sleeps = new ArrayList<>();
    private final Sleeper recordingSleeper = sleeps::add;

    private RateLimiter limiter(RateLimit limit) {
        return new RateLimiter(limit, clock, recordingSleeper);
    }

    private void acquire(RateLimiter l, int times) {
        for (int i = 0; i < times; i++) {
            l.acquire("GET", "/x");
        }
    }

    @Test
    void a_full_burst_goes_out_immediately() {
        RateLimiter l = limiter(RateLimit.defaults());

        acquire(l, 50);

        assertThat(sleeps).isEmpty();
    }

    @Test
    void the_request_after_the_burst_waits_one_interval() {
        RateLimiter l = limiter(RateLimit.defaults());
        acquire(l, 50);

        acquire(l, 1);

        assertThat(sleeps).singleElement().satisfies(d ->
                assertThat(d.toNanos()).isBetween(INTERVAL_900_PER_MIN_NANOS - 1, INTERVAL_900_PER_MIN_NANOS + 1));
    }

    @Test
    void unsatisfied_reservations_queue_one_interval_apart() {
        RateLimiter l = limiter(RateLimit.defaults());
        acquire(l, 50);

        acquire(l, 3);  // the clock never moves, so each waiter queues behind the previous one

        assertThat(sleeps).hasSize(3);
        for (int i = 0; i < 3; i++) {
            long expected = INTERVAL_900_PER_MIN_NANOS * (i + 1);
            assertThat(sleeps.get(i).toNanos()).isBetween(expected - 3, expected + 3);
        }
    }

    @Test
    void time_passing_refills_tokens() {
        RateLimiter l = limiter(RateLimit.perMinute(60).burst(5));
        acquire(l, 5);

        clock.advance(Duration.ofSeconds(2));  // 1 token per second
        acquire(l, 2);

        assertThat(sleeps).isEmpty();
        acquire(l, 1);
        assertThat(sleeps).singleElement().satisfies(d -> assertThat(d).isEqualTo(Duration.ofSeconds(1)));
    }

    @Test
    void refill_is_capped_at_the_burst_size() {
        RateLimiter l = limiter(RateLimit.perMinute(60).burst(5));
        acquire(l, 5);

        clock.advance(Duration.ofHours(1));
        acquire(l, 5);
        assertThat(sleeps).isEmpty();
        acquire(l, 1);

        assertThat(sleeps).hasSize(1);
    }

    @Test
    void partial_refill_gives_a_proportional_wait() {
        RateLimiter l = limiter(RateLimit.perMinute(60).burst(1));
        acquire(l, 1);

        clock.advance(Duration.ofMillis(250));
        acquire(l, 1);

        assertThat(sleeps).singleElement().satisfies(d -> assertThat(d).isEqualTo(Duration.ofMillis(750)));
    }

    @Test
    void a_wait_longer_than_max_wait_fails_client_side_without_taking_a_token() {
        RateLimiter l = limiter(RateLimit.perMinute(60).burst(1).maxWait(Duration.ofMillis(500)));
        acquire(l, 1);

        assertThatThrownBy(() -> l.acquire("GET", "/league/1"))
                .isExactlyInstanceOf(SleeperRateLimitException.class)
                .satisfies(e -> {
                    var rl = (SleeperRateLimitException) e;
                    assertThat(rl.isClientSide()).isTrue();
                    assertThat(rl.status()).isEqualTo(429);
                    assertThat(rl.method()).isEqualTo("GET");
                    assertThat(rl.path()).isEqualTo("/league/1");
                });
        assertThat(sleeps).isEmpty();

        // The rejected call took nothing, so after 500ms the 0.5 tokens are not yet enough but a
        // wait of 500ms is within bounds.
        clock.advance(Duration.ofMillis(500));
        acquire(l, 1);
        assertThat(sleeps).singleElement().satisfies(d -> assertThat(d).isEqualTo(Duration.ofMillis(500)));
    }

    @Test
    void a_wait_exactly_equal_to_max_wait_is_allowed() {
        RateLimiter l = limiter(RateLimit.perMinute(60).burst(1).maxWait(Duration.ofSeconds(1)));
        acquire(l, 1);

        acquire(l, 1);

        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void zero_max_wait_rejects_anything_that_cannot_go_out_now() {
        RateLimiter l = limiter(RateLimit.perMinute(60).burst(1).maxWait(Duration.ZERO));
        acquire(l, 1);

        assertThatThrownBy(() -> l.acquire("GET", "/x")).isInstanceOf(SleeperRateLimitException.class);
    }

    @Test
    void disabled_never_sleeps_or_rejects() {
        RateLimiter l = limiter(RateLimit.disabled());

        acquire(l, 10_000);

        assertThat(sleeps).isEmpty();
    }

    @Test
    void interrupt_while_waiting_restores_the_flag_and_throws_transport_exception() {
        Sleeper interrupting = d -> {
            throw new InterruptedException("stop");
        };
        RateLimiter l = new RateLimiter(RateLimit.perMinute(60).burst(1), clock, interrupting);
        l.acquire("GET", "/x");

        try {
            assertThatThrownBy(() -> l.acquire("GET", "/x"))
                    .isExactlyInstanceOf(SleeperTransportException.class)
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void two_hundred_virtual_threads_get_exactly_burst_free_grants_and_evenly_spaced_reservations()
            throws Exception {
        int threads = 200;
        RateLimit limit = RateLimit.perMinute(600).burst(10).maxWait(Duration.ofHours(1));
        ConcurrentLinkedQueue<Duration> waits = new ConcurrentLinkedQueue<>();
        RateLimiter l = new RateLimiter(limit, clock, waits::add);  // fixed clock: pure reservation math

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> l.acquire("GET", "/x")));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }

        // 600/min is one token per 100ms. With no time passing, only the burst is free; the k-th
        // extra caller must wait k intervals, so no window can exceed burst + rate * window.
        List<Long> sortedMillis = new ArrayList<>(waits.stream().map(Duration::toMillis).toList());
        Collections.sort(sortedMillis);
        assertThat(sortedMillis).hasSize(threads - 10);
        for (int k = 0; k < sortedMillis.size(); k++) {
            assertThat(sortedMillis.get(k)).isEqualTo(100L * (k + 1));
        }
    }

    @Test
    void with_a_real_clock_and_brief_real_sleeps_all_callers_finish_and_cannot_beat_the_rate()
            throws Exception {
        int threads = 200;
        // 1000 per second, burst 10: the 190 callers beyond the burst need at least ~190ms.
        RateLimiter l = new RateLimiter(RateLimit.perMinute(60_000).burst(10).maxWait(Duration.ofSeconds(5)),
                java.time.Clock.systemUTC(), Sleeper.real());
        long start = System.nanoTime();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> l.acquire("GET", "/x")));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }

        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(150)).isLessThan(Duration.ofSeconds(3));
    }
}
