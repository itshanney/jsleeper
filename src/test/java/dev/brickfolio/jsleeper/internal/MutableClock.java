package dev.brickfolio.jsleeper.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/** Test clock that moves only when told to. Thread-safe. */
final class MutableClock extends Clock {

    private final AtomicLong millis;

    MutableClock(Instant start) {
        this.millis = new AtomicLong(start.toEpochMilli());
    }

    void advance(Duration d) {
        millis.addAndGet(d.toMillis());
    }

    @Override
    public long millis() {
        return millis.get();
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis.get());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
