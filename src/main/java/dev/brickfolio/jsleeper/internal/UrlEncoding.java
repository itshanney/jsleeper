package dev.brickfolio.jsleeper.internal;

import java.nio.charset.StandardCharsets;

/** RFC 3986 percent-encoding. Unlike {@code URLEncoder}, a space becomes {@code %20}, not {@code +}. */
public final class UrlEncoding {

    private static final String HEX = "0123456789ABCDEF";

    private UrlEncoding() {}

    /** Encodes UTF-8 bytes of {@code value}; only unreserved characters pass through unchanged. */
    public static String encode(String value) {
        StringBuilder sb = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~';
            if (unreserved) {
                sb.append(c);
            } else {
                sb.append('%').append(HEX.charAt(c >> 4)).append(HEX.charAt(c & 0xF));
            }
        }
        return sb.toString();
    }
}
