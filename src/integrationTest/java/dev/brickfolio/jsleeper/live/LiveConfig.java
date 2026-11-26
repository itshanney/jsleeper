package dev.brickfolio.jsleeper.live;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Finds the league ID for live tests: the {@code SLEEPER_LEAGUE_ID} system property or environment
 * variable, else the {@code sleeper.league_id} key of {@code .env} in the working directory.
 * The value is never printed or logged.
 */
final class LiveConfig {

    private static final String KEY = "SLEEPER_LEAGUE_ID";
    private static final String ENV_FILE_KEY = "sleeper.league_id=";

    private LiveConfig() {}

    static Optional<String> leagueId() {
        return firstNonBlank(System.getProperty(KEY), System.getenv(KEY), fromEnvFile());
    }

    private static Optional<String> firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return Optional.of(candidate.trim());
            }
        }
        return Optional.empty();
    }

    /** Reads only the one key; no other line of the file is looked at beyond matching its prefix. */
    private static String fromEnvFile() {
        Path file = Path.of(".env");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .map(String::strip)
                    .filter(line -> line.startsWith(ENV_FILE_KEY))
                    .findFirst()
                    .map(line -> line.substring(ENV_FILE_KEY.length()).strip().replaceAll("^['\"]|['\"]$", ""))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
