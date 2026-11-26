package dev.brickfolio.jsleeper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** Test helper that loads recorded API responses from {@code src/test/resources/fixtures}. */
public final class Fixtures {

    private Fixtures() {}

    /** Returns the raw bytes of a fixture file, for example {@code "user.json"}. */
    public static byte[] bytes(String name) {
        String resource = "/fixtures/" + name;
        try (InputStream in = Fixtures.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("fixture not found on classpath: " + resource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
