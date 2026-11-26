package dev.brickfolio.jsleeper.internal;

import java.time.Duration;

/** Test seam for waiting between retries. */
@FunctionalInterface
public interface Sleeper {

    /** Blocks the calling thread for {@code duration}. */
    void sleep(Duration duration) throws InterruptedException;

    /** Returns a sleeper that really blocks. */
    static Sleeper real() {
        return Thread::sleep;
    }
}
