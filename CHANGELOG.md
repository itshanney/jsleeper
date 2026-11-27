# Changelog

All notable changes to jsleeper are recorded here. The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html):
- **Minor** version for new fields or endpoints.
- **Major** version for removed or retyped fields.
- **Patch** version for fixes.

## 0.1.0 - 2026-11-27

The first version: a Java 25 client for the read-only [Sleeper API](https://docs.sleeper.com/). It is not yet published to Maven Central; install it locally with `./gradlew publishToMavenLocal`.

### Added

- **Client:** `SleeperClient` is thread-safe and built through `SleeperClientBuilder`. You can set the base URI, `HttpClient`, timeouts, User-Agent, `ObjectMapper`, `Clock`, retry policy, rate limit and listener.
- **All 17 documented Sleeper routes**, grouped by resource:
  - `users()`: a user by username or ID, plus a user's leagues and drafts.
  - `leagues()`: a league, rosters, users, matchups, winners and losers brackets, transactions, traded picks and drafts.
  - `drafts()`: a draft, its picks and its traded picks.
  - `players()`: all players, with optional position and active filters, plus trending adds and drops.
  - `state()`: the current sport state.
- **Typed models:** responses are immutable Java records.
  - Collections are never null.
  - Enums are open: an unrecognised value becomes `UNKNOWN`, and the raw string is kept alongside it.
  - Epoch-millisecond timestamps become `Instant`.
  - Undocumented settings and metadata stay as maps.
- **Playoff brackets:** each slot is a sealed `BracketSlot` (`Team`, `WinnerOf`, `LoserOf`, `Tbd`), and the "winner/loser of match N" source is kept too.
- **Helpers:**
  - Season totals: `RosterSettings.pointsFor()`, `pointsAgainst()` and `potentialPoints()`, combining the whole and hundredths fields.
  - `Matchup.effectivePoints()` (the commissioner's override when set) and `bench()`.
  - `Matchups.pair()` for head-to-head pairs.
  - `Transaction.waiverBid()`.
  - `Avatars.full()` and `Avatars.thumb()`.
- **Shortcut methods:** `leagues().matchups(id)` and `leagues().transactions(id)` use the current `display_week`. Season arguments are also accepted as a `String`.
- **Consistent not-found handling:** Sleeper reports a missing resource in three ways (200 with `null`, 200 with `[]`, or 404 with `null`). All three become an empty `Optional`, list or map. A 404 with any other body means an unknown route and throws `SleeperApiException`.
- **Errors:** a hierarchy of unchecked exceptions (`SleeperApiException`, `SleeperBadRequestException`, `SleeperRateLimitException`, `SleeperServerException`, `SleeperTransportException`, `SleeperDeserializationException`). Body snippets are capped at 512 bytes.
- **Retries:** `RetryPolicy` retries 429, 5xx and transport failures with jittered exponential backoff and honours `Retry-After`. The default is 3 attempts.
- **Rate limiting:** a client-side token bucket (`RateLimit`), 900 calls a minute by default, which keeps you under Sleeper's 1000-a-minute IP-block threshold.
- **Observability:**
  - `RequestListener` emits events for each attempt, keyed by route template.
  - `System.Logger` logs each request, each retry and long rate-limit waits.
  - A one-time warning is logged if the full player list is fetched more than once an hour.
- **Player cache:** `PlayerDirectory` keeps the ~5 MB player list in memory for 24 hours by default.
  - One thread refreshes while the others keep reading the current data.
  - If a refresh fails, the old data is kept.
  - Snapshots can be saved and loaded through streams, so a restart doesn't need a new download.
- **Packaging:** a JPMS module, `dev.brickfolio.jsleeper`, whose only runtime dependency is Jackson. The build produces sources and Javadoc jars, and the Maven publication is configured with optional signing.
- **Tooling:**
  - `scripts/record-fixtures.sh` records anonymised test fixtures from a real league.
  - `scripts/check-sleeper-docs.sh` detects changes to Sleeper's published docs.
  - CI: a GitHub Actions build, a nightly integration-test and docs-check workflow, and a Jenkinsfile.
