# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

jsleeper (`dev.brickfolio:jsleeper`, package `dev.brickfolio.jsleeper`) is a Java 25 client library for the read-only Sleeper fantasy football API (`https://api.sleeper.app/v1`). It is a single JPMS module, and its only runtime dependency is Jackson. The design source of truth is `specs/2026-11-26-sleeper-java-client.md`:
- the decisions D1–D7;
- the data model and API contracts;
- the usage examples;
- Appendix A, the API baseline as documented by Sleeper.

Read the relevant section before changing behaviour. If you change behaviour, update the spec in the same change.

## Commands

```bash
./gradlew build                      # compile (-Xlint:all -Werror), unit tests, Javadoc (-Xdoclint:all -Werror), jars
./gradlew test --tests 'dev.brickfolio.jsleeper.api.LeaguesApiTest'            # one test class
./gradlew test --tests 'dev.brickfolio.jsleeper.api.LeaguesApiTest.recorded_*' # matching methods
./gradlew integrationTest            # src/integrationTest: real Sleeper API calls (read-only); not part of build
./gradlew publishMavenJavaPublicationToStagingRepository   # dry-run publish to build/staging-repo
scripts/record-fixtures.sh [--week N]   # re-record anonymised fixtures from a real league
scripts/check-sleeper-docs.sh           # detect drift in docs.sleeper.com (exit 0 none, 10 cosmetic, 20 content)
```

- **Warnings are errors.** Any compiler warning fails the build. So does a missing Javadoc `@param` or `@return` on a public type in an exported package; `internal` is excluded.
- **Integration tests** need a league ID from `SLEEPER_LEAGUE_ID`, or the `sleeper.league_id` key in the git-ignored `.env` file. Without one, the league tests skip.
- **Never print or modify `.env`.** Scripts and tests parse only that single key.
- **Jenkinsfile:** runs a `Build` stage (`assemble testClasses`) and a `Test` stage (`test`).
- **Nightly GitHub Actions** (`.github/workflows/nightly.yml`): runs `integrationTest` and the docs check.

## Architecture

**Request path.** Every endpoint follows the same path:
1. `SleeperClient` is built by `SleeperClientBuilder`, once per process, and is thread-safe.
2. A resource facade in `api/` handles the call: `UsersApi`, `LeaguesApi`, `DraftsApi`, `PlayersApi` or `StateApi`.
3. Each facade method validates its arguments through `api/Args` before any network call.
4. It then calls `internal/Requester.get(pathTemplate, pathSegments, query, javaType, EmptyKind, timeoutOverride)`.

**Inside `Requester`.** It owns everything cross-cutting:
- path-segment percent-encoding (`internal/UrlEncoding`);
- the client-side token-bucket `RateLimiter`, which runs before every attempt, retries included;
- the `RetryPolicy` loop (jittered backoff, `Retry-After`);
- `RequestListener` events and `System.Logger` logging, both keyed by the path template;
- deserialisation through the `ObjectMapper` from `internal/JsonCodec`.

**Adding an endpoint.**
- Give it a facade method that passes a `{snake_case}` path template, such as `/league/{league_id}/rosters`, together with the concrete segments.
- Add the template to `PathTemplatesTest`.

**Empty results and errors** (D7 in the spec; this behaviour is easy to break). Sleeper reports a missing resource inconsistently: 200 with `null`, 200 with `[]`, or 404 with `null`. `Requester` maps all of these to the `EmptyKind` result (`Optional.empty()`, an empty list or an empty map). `REQUIRED` throws instead. A 404 with any **other** body is an unknown route and throws `SleeperApiException`. With `Accept: application/json`, an unknown route returns `{"errors":...}`, which is how a typo in a path template surfaces. Every other failure maps into the unchecked `error/` hierarchy; raw `IOException` never leaks.

**Model conventions** (`model/`, `model/bracket/`):
- **Records with tolerant mapping.** Models are records, and the mapper is set to snake_case with unknown fields ignored.
- **Never-null collections.** Records normalise null collections to unmodifiable empties through `model/Immutables`, which keeps null map values.
- **Open enums.** Enums implement `WireEnum`, map their wire values with `@JsonProperty`, and have an `UNKNOWN` constant marked `@JsonEnumDefaultValue`.
- **Raw string alongside each enum.** Records keep the original string next to the enum (for example `statusRaw`). A `@JsonCreator` factory takes the raw string and derives the enum through `WireEnum.fromWire`. Follow this pattern for any new enum field.
- **Timestamps.** Epoch-millisecond fields become `Instant`.
- **Undocumented objects stay loose.** League `settings`, scoring settings and metadata stay `Map<String, Object>` because Sleeper doesn't document them.
- **Brackets.** `BracketMatch` uses a custom deserialiser to turn `t1`/`t2`/`t1_from`/`t2_from` into the sealed `BracketSlot` (`Team`, `WinnerOf`, `LoserOf`, `Tbd`).
- **New packages must be declared.** A new package that Jackson reflects into needs both `exports` and `opens ... to com.fasterxml.jackson.databind` in `module-info.java`.

**Concurrency.**
- The rate limiter and `PlayerDirectory` use `ReentrantLock`, not `synchronized`, so callers on virtual threads are never pinned.
- `PlayerDirectory` (`players/`) caches the ~5 MB `/players/{sport}` map with a TTL (default 24h), because Sleeper asks for at most one fetch a day:
  - a single thread does the refresh while the others keep reading stale data;
  - if a refresh fails, the old data is kept and the next attempt waits `min(ttl, 1h)`;
  - snapshots can be saved and loaded through caller-supplied streams.
- `PlayersApi` logs a one-time warning if the unfiltered player map is fetched more than once an hour.

**Time and randomness are injected.** `Clock`, `Sleeper` and `RandomGenerator` are passed into `Requester` and `RateLimiter`. Tests use `MutableClock` and recording sleepers, so no test actually waits for a backoff; keep it that way.

## Tests

- **Unit tests** (`src/test`) are offline. Every HTTP call goes to a WireMock server on a dynamic port, serving fixtures through `Fixtures.bytes("name.json")`.
- **Integration tests** (`src/integrationTest`) are the only tests that call the real API.
- **Fixtures** live in `src/test/resources/fixtures/`. The recorded files come from a real league with personal fields anonymised; re-record them with the script, never by hand. `edge_*.json` files are hand-made edge cases that the script doesn't touch.
- **Usage examples.** `examples/UsageExamplesTest` is the compiled copy of the spec's Usage Examples section. Changing a public signature means updating both.
- **Transport failures in tests.** Use WireMock `Fault.CONNECTION_RESET_BY_PEER`, or `http://127.0.0.1:1` for a refused connection. Don't open and close a `ServerSocket(0)` to get a closed port; that race made tests flaky.

## Sleeper API constraints

- **Usage terms.** Non-commercial use only. Trending data requires attribution to Sleeper.
- **Rate limit.** Stay well under 1000 calls per minute or Sleeper may block the IP. The client defaults to 900 per minute.
- **Field types differ from the docs.** Real responses often don't match the docs' examples; for instance, draft pick `roster_id` is an int and player external IDs are mixed int and string. Trust recorded fixtures over the docs, and coerce in the model.
