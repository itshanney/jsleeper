package dev.brickfolio.jsleeper.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Null-normalising unmodifiable copies. {@code List.copyOf} and {@code Map.copyOf} reject null
 * elements and values, which Sleeper's loosely typed maps may contain.
 */
final class Immutables {

    private Immutables() {}

    static <T> List<T> list(List<T> source) {
        return source == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(source));
    }

    static <K, V> Map<K, V> map(Map<K, V> source) {
        return source == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
