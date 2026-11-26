# jsleeper

A small Java client for the read-only [Sleeper](https://sleeper.com) fantasy football API. It wraps all 17 documented GET routes in typed, immutable records, built on the JDK's `java.net.http.HttpClient` and Jackson.

- Typed models: users, leagues, rosters, matchups, playoff brackets (a sealed `BracketSlot` you can `switch` over), transactions, drafts, players and trending players.
- Retries with exponential backoff and jitter for 429, 5xx and transport errors, honouring `Retry-After`.
- A client-side rate limiter that keeps you under Sleeper's roughly 1000 calls a minute limit.
- `PlayerDirectory`: an in-memory, time-limited cache of the ~5 MB players map, with snapshots you can save and restore.
- A `RequestListener` hook for metrics and logging, and `System.Logger` for log output (no logging dependency).
- Synchronous and thread-safe. Use virtual threads or your own executor for concurrency.

## Requirements

Java 25. The library is a JPMS module named `dev.brickfolio.jsleeper`; it works on the classpath too.

## Installation

Coordinates: `dev.brickfolio:jsleeper:0.1.0`.

**Not yet published to Maven Central.** Until then, build from source and install to your local Maven repository:

```
./gradlew publishToMavenLocal
```

```kotlin
// Gradle (Kotlin DSL)
dependencies {
    implementation("dev.brickfolio:jsleeper:0.1.0")
}
```

```xml
<!-- Maven -->
<dependency>
  <groupId>dev.brickfolio</groupId>
  <artifactId>jsleeper</artifactId>
  <version>0.1.0</version>
</dependency>
```

The build version in this repository is `0.1.0-SNAPSHOT`. A release sets the version to `0.1.0` (for example with a tag build), then publishes.

## Quick start

Build one client and share it.

```java
// Defaults: production base URL, 900 req/min limiter, 3-attempt retry, 10s timeout.
SleeperClient sleeper = SleeperClient.create();

// Or customised:
SleeperClient custom = SleeperClient.builder()
        .userAgent("my-app/1.0")
        .requestTimeout(Duration.ofSeconds(5))
        .rateLimit(RateLimit.perMinute(600).burst(20).maxWait(Duration.ofSeconds(10)))
        .retryPolicy(RetryPolicy.builder()
                .maxAttempts(4)
                .initialBackoff(Duration.ofMillis(250))
                .maxBackoff(Duration.ofSeconds(5))
                .build())
        .listener(new RequestListener() {
            @Override
            public void onResponse(ResponseEvent event) {
                System.out.println(event.pathTemplate() + " " + event.status());
            }
        })
        .build();
```

Username to this season's leagues:

```java
User user = sleeper.users().get("someuser")
        .orElseThrow(() -> new IllegalArgumentException("No such Sleeper user"));

SportState nfl = sleeper.state().get(Sport.NFL);

List<League> leagues = sleeper.users().leagues(user.userId(), Sport.NFL, nfl.leagueSeason());

for (League league : leagues) {
    System.out.printf("%s  [%s]  %d teams  status=%s%n",
            league.name(), league.leagueId(), league.totalRosters(), league.status());
}
```

A weekly scoreboard with player names, using the cached player directory:

```java
PlayerDirectory players = PlayerDirectory.builder(sleeper)
        .sport(Sport.NFL)
        .ttl(Duration.ofHours(24))
        .build();                                   // lazy: no HTTP call yet

int week = sleeper.state().get(Sport.NFL).displayWeek();
List<MatchupPair> pairs = Matchups.pair(sleeper.leagues().matchups(leagueId, week));

for (MatchupPair pair : pairs) {
    Matchup home = pair.home();
    Matchup away = pair.away();                     // null on a bye / median-only row
    System.out.printf("Roster %d  %.2f  vs  %s%n",
            home.rosterId(), home.effectivePoints(),
            away == null ? "BYE" : "%.2f  Roster %d".formatted(away.effectivePoints(), away.rosterId()));

    for (String playerId : home.starters()) {
        String name = players.get(playerId)          // first call loads ~5 MB once, then memory
                .map(Player::fullName)
                .orElse(playerId);                   // e.g. "DET" for a team defense
        System.out.printf("    %-22s %6.2f%n", name, home.playersPoints().getOrDefault(playerId, 0.0));
    }
}
```

All twelve examples from the [spec](specs/2026-11-26-sleeper-java-client.md) (standings, transactions, brackets, draft boards, trending, virtual threads, error handling, snapshots, avatars) are compiled and run in `src/test/java/dev/brickfolio/jsleeper/examples/UsageExamplesTest.java`.

## Things to know

### Non-commercial use only

Sleeper's API is free for **non-commercial** use. For commercial use, contact Sleeper to discuss licensing; see the [Sleeper API documentation](https://docs.sleeper.com/). jsleeper enforces nothing; the terms are between you and Sleeper.

### Attribution for trending data

Sleeper requires attribution wherever its trending-player data is shown. If you display the output of `players().trending(...)`, credit Sleeper.

### Store the user ID, not the username

Usernames can change. Look a user up once, then keep `User.userId()` and use it from then on.

### Fetch the players map at most once a day

`players().all(Sport)` returns about 5 MB, and Sleeper asks callers to fetch it at most once per day and to save it. Use `PlayerDirectory`, which caches it with a TTL (24 hours by default) and serves stale data if a refresh fails. The client logs a warning if the full map is fetched twice within an hour. `PlayerDirectory.writeSnapshot` and `loadSnapshot` let you persist the map across restarts without choosing a storage location for you.

### Missing data and errors

All exceptions are unchecked and extend `SleeperException`, which carries the HTTP method and path.

| Situation | Result |
|---|---|
| 200 with a `null` or empty body | `Optional.empty()` / empty list or map |
| 200 with `[]` | empty list |
| 404 with a blank or `null` body (a missing league, draft, ...) | `Optional.empty()` / empty list or map |
| 404 with any other body (an unknown route: a bug or an API change) | `SleeperApiException` (status 404) |
| 400 / 429 / 5xx after retries | `SleeperBadRequestException` / `SleeperRateLimitException` / `SleeperServerException` |
| The local rate limiter could not grant a token in time | `SleeperRateLimitException` with `isClientSide() == true`; nothing was sent |
| I/O failure or timeout | `SleeperTransportException` |
| 2xx with a body that cannot be parsed | `SleeperDeserializationException` |

Sleeper cannot tell an unknown league ID from a league with no rows in many cases, so a mistyped ID can look like "no data". Enums are open: an unrecognised value reads as `UNKNOWN`, and the raw string stays available (for example `League.statusRaw()`).

## Development

```
./gradlew build             # compile (-Xlint:all -Werror), unit tests, Javadoc (doclint), jars
./gradlew integrationTest   # read-only calls to the real Sleeper API; not part of build
scripts/record-fixtures.sh       # re-record anonymised test fixtures
scripts/check-sleeper-docs.sh    # detect changes in Sleeper's published docs
```

- Tests are split into two source sets. `src/test` holds the unit tests: they are offline, and every HTTP call goes to a local WireMock server. `src/integrationTest` holds the integration tests, which call the real Sleeper API.
- `integrationTest` needs a public league ID in the `SLEEPER_LEAGUE_ID` environment variable, or in a git-ignored `.env` file as `sleeper.league_id=<id>`. Without one, the league tests are skipped; the not-found probe still runs. A nightly GitHub Actions workflow runs the live tests and the docs check, and opens an issue on failure (it needs a `SLEEPER_LEAGUE_ID` repository secret).
- `scripts/record-fixtures.sh` takes the league ID from `--league-id`, the same environment variable, or `.env`. It anonymises user data before writing fixtures. See `src/test/resources/fixtures/README.md`.
- A dry-run publish writes the jar, sources, Javadoc and POM to `build/staging-repo`: `./gradlew publishMavenJavaPublicationToStagingRepository`. Signing runs only when `-PsigningKey` and `-PsigningPassword` are supplied.

The design and decisions are in [specs/2026-11-26-sleeper-java-client.md](specs/2026-11-26-sleeper-java-client.md).

## License

[MIT](LICENSE). Sleeper's API has its own terms of use; see above.
