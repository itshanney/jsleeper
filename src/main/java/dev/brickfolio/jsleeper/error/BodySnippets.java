package dev.brickfolio.jsleeper.error;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Truncates response bodies for inclusion in exceptions. */
final class BodySnippets {

    static final int MAX_BYTES = 512;

    private static final int UTF8_CONTINUATION_MASK = 0xC0;
    private static final int UTF8_CONTINUATION_BITS = 0x80;

    private BodySnippets() {}

    /**
     * Returns at most {@value #MAX_BYTES} UTF-8 bytes of {@code body} as a string, never splitting
     * a multi-byte character. A null body yields null.
     */
    static String truncate(String body) {
        if (body == null) {
            return null;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_BYTES) {
            return body;
        }
        int end = MAX_BYTES;
        // bytes[end] is the first dropped byte; if it is a continuation byte we are mid-character.
        while (end > 0 && (bytes[end] & UTF8_CONTINUATION_MASK) == UTF8_CONTINUATION_BITS) {
            end--;
        }
        return new String(Arrays.copyOf(bytes, end), StandardCharsets.UTF_8);
    }
}
