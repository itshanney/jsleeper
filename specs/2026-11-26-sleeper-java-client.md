# Tech Spec: jsleeper, a Java client for the Sleeper Fantasy API

- **Status:** Draft
- **Date:** 2026-11-26
- **Source API:** https://docs.sleeper.com/ (base URL `https://api.sleeper.app/v1`)
- **Architectural style:** A synchronous client library: typed wrappers over a read-only REST/JSON API with no auth. There is no server, database or queue.

---

## Overview

jsleeper is a small, dependency-light Java library. It wraps the 17 read-only GET endpoints of the Sleeper API in typed, immutable models (Java records). It is built on the JDK's `java.net.http.HttpClient` and Jackson. One thread-safe `SleeperClient` holds the shared parts: HTTP transport, JSON mapping, a client-side rate limiter that keeps callers under Sleeper's documented ~1000 calls/min IP-block limit, and a retry policy for 429/5xx. Endpoints are grouped into resource facades (`users()`, `leagues()`, `drafts()`, `players()`, `state()`) that match the docs' sections. The players endpoint returns a ~5 MB response that Sleeper asks callers to fetch at most once a day, so the library also ships an opt-in `PlayerDirectory` that caches it in memory with a TTL and can load from or save to a caller-supplied stream. The API is synchronous only. Callers who want concurrency use virtual threads (Java 25) or their own executors. The library targets **Java 25**, builds with **Gradle**, and ships as `dev.brickfolio:jsleeper` (package `dev.brickfolio.jsleeper`) for non-commercial use.

---

## Decisions (confirmed 2026-11-26)

| # | Decision | Consequence |
|---|---|---|
| D1 | **Java 25** (LTS) baseline. | Records, sealed interfaces, final pattern matching for `switch` (so `BracketSlot` gets exhaustive switches), and virtual threads for callers who want to run calls concurrently. Gradle toolchain set to `JavaLanguageVersion.of(25)`. Needs **Gradle ≥ 9.1**, the first release that runs on JDK 25. |
| D2 | **Gradle (Kotlin DSL)**, single module, published to Maven Central. | Wrapper checked in. `java-library`, `maven-publish` and `signing` plugins. Dependency versions in `gradle/libs.versions.toml`. |
| D3 | Maven coordinates `dev.brickfolio:jsleeper`; root package **`dev.brickfolio.jsleeper`**; JPMS module name `dev.brickfolio.jsleeper`. | Central namespace `dev.brickfolio` must be verified through DNS TXT on `brickfolio.dev` before the first publish. |
| D4 | **Non-commercial / hobby use only**, per Sleeper's terms. | The README says so and carries Sleeper's attribution request for trending data. The library itself enforces nothing. |
| D5 | NFL only for leagues and drafts. The docs list `nfl` as the only supported sport there. `state` and `players` take a sport parameter anyway. | `Sport` is an open enum (see Data Model) so other sports don't break. |
| D6 | `fpts_decimal` / `fpts_against_decimal` hold **hundredths** of a point. | `pointsFor() = fpts + fptsDecimal / 100` as `BigDecimal`, scale 2. |
| D7 | **Not-found behaviour is inconsistent across endpoints.** Verified live on 2026-11-26 (table below). | Determines the not-found handling in the Requester (see API Contracts → "Empty-result rules"). |

### Observed not-found behaviour (live probe, 2026-11-26, with nonexistent IDs)

| Endpoint | Status | Content-Type | Body |
|---|---|---|---|
| `/user/{unknown username or id}` | 200 | `application/json` | `null` |
| `/state/{unknown sport}` | 200 | `application/json` | `null` |
| `/league/{id}`, `/league/{id}/rosters`, `/league/{id}/matchups/{w}`, `/league/{id}/winners_bracket` | **404** | `application/json` | `null` |
| `/draft/{id}`, `/draft/{id}/picks`, `/draft/{id}/traded_picks` | **404** | `application/json` | `null` |
| `/user/{id}/leagues/…`, `/user/{id}/drafts/…`, `/league/{id}/users`, `/league/{id}/transactions/{w}`, `/league/{id}/traded_picks`, `/league/{id}/drafts` | 200 | `application/json` | `[]` |
| `/players/nfl/trending/{invalid type}` | 200 | `application/json` | `[]` |
| A route that doesn't exist (e.g. `/bogus/path`, or a typo in our own path), requested **without** an `Accept` header | **404** | **`text/html`** | Sleeper web-app HTML page |
| The same unknown route **with `Accept: application/json`** (which the client always sends) | **404** | **`application/json`** | `{"errors":{"message":"Not found."}}` (found by the live tests, 2026-11-26) |

The last two rows matter most. An unknown route is a 404 with a JSON content type whenever the client asks for JSON, so the content type cannot tell it apart from a missing resource; only the body can. A missing resource has a body of `null` (or nothing), while an unknown route has an error object or a page. Other things the probe showed: `/state/nfl` also returns `season_has_scores` (boolean), which the docs omit. Trending `player_id` values include team codes (e.g. `"WAS"`).

---

## Component Diagram

```
┌──────────────────────────────────────────────────────────────────────────┐
│ Caller code                                                              │
└───────────────┬──────────────────────────────────────────────────────────┘
                │
┌───────────────▼──────────────────────────────────────────────────────────┐
│ SleeperClient: entry point; built once, thread-safe; owns config and     │
│   exposes the resource facades.                                          │
│ ┌───────────┐┌────────────┐┌───────────┐┌────────────┐┌───────────┐      │
│ │UsersApi   ││LeaguesApi  ││DraftsApi  ││PlayersApi  ││StateApi   │      │
│ └─────┬─────┘└─────┬──────┘└─────┬─────┘└─────┬──────┘└─────┬─────┘      │
│       └────────────┴─────────────┴──────┬─────┴─────────────┘            │
│  Resource facades: build the path, call the requester, return models.    │
│                                         │                                │
│ ┌───────────────────────────────────────▼──────────────────────────────┐ │
│ │ Requester (internal): runs one GET with rate limiting, retries,      │ │
│ │   status mapping, null-body→Optional, deserialisation, listeners.    │ │
│ └──┬──────────────┬───────────────┬───────────────┬──────────────┬─────┘ │
│    │              │               │               │              │       │
│ ┌──▼─────────┐ ┌──▼──────────┐ ┌──▼────────────┐ ┌▼────────────┐ ┌▼─────┐│
│ │RateLimiter │ │RetryPolicy  │ │JsonCodec      │ │HttpClient   │ │Reques││
│ │token bucket│ │backoff for  │ │preconfigured  │ │(JDK) sends  │ │tList-││
│ │per client  │ │429/5xx/IO   │ │Jackson mapper │ │the bytes    │ │ener  ││
│ └────────────┘ └─────────────┘ └───────────────┘ └─────────────┘ └──────┘│
└──────────────────────────────────────────────────────────────────────────┘
        (optional, separate object, built on PlayersApi)
┌──────────────────────────────────────────────────────────────────────────┐
│ PlayerDirectory: in-memory, TTL-cached player map (default 24h);         │
│   lookups by id; load/save snapshot through caller-supplied streams.     │
└──────────────────────────────────────────────────────────────────────────┘
  Static helpers: Avatars (CDN URL builder), Matchups (pairing by matchup_id)
```

| Component | Responsibility | Public surface | Failure mode |
|---|---|---|---|
| `SleeperClient` | Owns config and shared infrastructure; hands out facades. | `SleeperClient.create()`, `SleeperClient.builder()…build()`, `users()`, `leagues()`, `drafts()`, `players()`, `state()` | Bad config fails fast at `build()` with `IllegalArgumentException`. |
| `UsersApi`, `LeaguesApi`, `DraftsApi`, `PlayersApi`, `StateApi` | Map one Java method to one endpoint. They are stateless. | See API Contracts. | Pass through exceptions from `Requester`. Validate arguments first (non-blank IDs, week ≥ 1) and throw `IllegalArgumentException`. |
| `Requester` (package-private) | Run one GET end to end. | Internal `<T> T get(String path, Map<String,String> query, JavaType type)` and `<T> Optional<T> getOptional(...)` | Turns every failure into the `SleeperException` hierarchy. Never leaks raw `IOException`. |
| `RateLimiter` (package-private) | Token bucket shared by all calls on one client. Default 900 req/min, burst 50. | Configured through the builder; `RateLimit.disabled()` turns it off. | Blocks the calling thread until a token is free. If the wait would exceed `maxWait` (default 30s), throws `SleeperRateLimitException` without sending the request. |
| `RetryPolicy` | Decides whether and when to retry. | `RetryPolicy.defaults()`, `RetryPolicy.none()`, `RetryPolicy.builder()` | After the last attempt, throws the last mapped exception. |
| `JsonCodec` (package-private) | Holds one tolerant, configured `ObjectMapper`. | None. Callers may pass their own mapper (see Tradeoffs). | Bad JSON becomes `SleeperDeserializationException` (keeps the path and the first 512 bytes of the body). |
| `RequestListener` | Hook for metrics and logging. | Interface with `onRequest(RequestEvent)` and `onResponse(ResponseEvent)` (method, path, status, attempt, duration, bytes). | Listener exceptions are caught and logged. They never fail a request. |
| `PlayerDirectory` | Respects Sleeper's "once per day" rule for `/players`. | `get(id)`, `all()`, `refresh()`, `writeSnapshot(OutputStream)`, `loadSnapshot(InputStream)` | If refresh fails and stale data exists, keeps the stale data, logs a warning and serves it. With no data at all, rethrows. |
| `Avatars` | Builds CDN URLs. | `full(String avatarId)`, `thumb(String avatarId)` returning `URI` | Null or blank ID throws `IllegalArgumentException`. |
| `Matchups` | Groups a week's `Matchup` rows into head-to-head pairs. | `pair(List<Matchup>) -> List<MatchupPair>` | Rows with a null `matchupId` (bye weeks, median scoring) go in `MatchupPair` with `away == null`. |

**Logging:** `System.Logger`, so the library needs no SLF4J dependency. Consumers route it through their own backend. Request lines log at DEBUG, retries at INFO, refresh fallback at WARNING.

**Dependencies:** `jackson-databind` and `jackson-datatype-jsr310` only. Test-only: JUnit 5, AssertJ, WireMock.

### Package layout

```
dev.brickfolio.jsleeper/
  SleeperClient, SleeperClientBuilder, RetryPolicy, RateLimit, RequestListener (+ events)
  api/        UsersApi, LeaguesApi, DraftsApi, PlayersApi, StateApi
  model/      records + enums (below)
  model/bracket/  BracketMatch, BracketSlot (sealed)
  players/    PlayerDirectory
  util/       Avatars, Matchups
  error/      SleeperException hierarchy
  internal/   Requester, RateLimiter, JsonCodec, deserialisers  (not exported in module-info)
```

The library ships a JPMS `module-info.java` that exports everything except `internal`.

---

## Data Model

### Global mapping rules (the engineer should apply these everywhere)

1. **All models are records** annotated `@JsonIgnoreProperties(ignoreUnknown = true)`. The mapper is also set with `FAIL_ON_UNKNOWN_PROPERTIES = false`. Sleeper adds fields without notice, and the docs list only a subset.
2. **Field naming:** the JSON uses snake_case and Java uses camelCase, via `PropertyNamingStrategies.SNAKE_CASE`.
3. **Nullable everything except IDs.** Use boxed types (`Integer`, `Double`, `Boolean`) for any field the docs or real responses show as null or missing. Collections are never null: the canonical constructor normalises null to `List.of()` / `Map.of()`.
4. **Timestamps:** epoch-millisecond fields (`created`, `status_updated`, `start_time`, `last_picked`, `last_message_time`) become `Instant`. Disable `READ_DATE_TIMESTAMPS_AS_NANOSECONDS` so numbers are read as millis. `season_start_date` becomes `LocalDate`.
5. **Scalar coercion on.** Example: a draft pick's `roster_id` is a string in the docs and an int elsewhere. Model it as `Integer` and let Jackson coerce `"10"` to `10`.
6. **Open enums:** each enum has an `UNKNOWN` constant marked `@JsonEnumDefaultValue`, with `READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE` on. Every enum record field also keeps the raw string (for example `statusRaw()`) so an `UNKNOWN` value can still be read.
7. **Undocumented objects stay loosely typed:** league `settings`, `scoring_settings` and `metadata` maps. We don't invent a schema the docs don't commit to. They are `Map<String, Object>`, or `Map<String, Double>` for scoring.
8. **IDs are `String`** (user, league, draft, player, transaction). `roster_id`, `matchup_id`, `week`, `round` and `pick_no` are `int`/`Integer`. Player IDs include team codes such as `"DET"` for team defenses, so they can't be numeric.

### Enums

| Enum | Known values (wire form) | Notes |
|---|---|---|
| `Sport` | `nfl`, `nba`, `lcs` | Used in paths; serialised lowercase. |
| `SeasonType` | `pre`, `regular`, `post`, `off` | `off` is not in the docs but shows up in practice; `UNKNOWN` covers other values. |
| `LeagueStatus` | `pre_draft`, `drafting`, `in_season`, `complete` | Documented. |
| `TransactionType` | `trade`, `free_agent`, `waiver` | `waiver` is mentioned in a doc comment. |
| `TransactionStatus` | `complete`, `failed` | Only `complete` is documented. `failed` is inferred from "why a waiver failed". |
| `DraftType` | `snake`, `linear`, `auction` | Only `snake` is documented; the rest are from the Sleeper product. `UNKNOWN` covers misses. |
| `DraftStatus` | `pre_draft`, `drafting`, `paused`, `complete` | Only `complete` is documented. |
| `TrendType` | `add`, `drop` | Used in the path. |

### Entities

```
User            userId, username, displayName, avatar
LeagueUser      userId, username, displayName, avatar, isOwner(Boolean = commissioner),
                metadata: Map<String,Object>   (+ convenience teamName() -> Optional<String>)
League          leagueId, name, season, seasonType, status, sport, totalRosters, draftId,
                previousLeagueId (nullable; links dynasty seasons), avatar,
                rosterPositions: List<String>, scoringSettings: Map<String,Double>,
                settings: Map<String,Object>
Roster          rosterId(int), leagueId, ownerId (nullable: orphaned team), players, starters,
                reserve: List<String>, settings: RosterSettings
RosterSettings  wins, losses, ties, totalMoves, waiverPosition, waiverBudgetUsed,
                fpts, fptsDecimal, fptsAgainst, fptsAgainstDecimal (all Integer)
                + derived pointsFor() / pointsAgainst() as BigDecimal (scale 2) = fpts + fptsDecimal/100
                  (D6: the decimal field holds hundredths; null decimal is treated as 0)
Matchup         rosterId, matchupId(Integer, nullable), points(Double), customPoints(Double),
                players, starters (ordered by roster slot), playersPoints: Map<String,Double>,
                startersPoints: List<Double>  (last two are undocumented; confirm they're present when recording live fixtures, and drop them if not)
                + derived bench() = players − starters
                + derived effectivePoints() = customPoints if non-null, else points (null → 0.0)
BracketMatch    round(r), matchId(m), team1(t1), team2(t2): BracketSlot, winner(w), loser(l): Integer,
                placement(p): Integer   (undocumented; observed to mean the place being played for)
BracketSlot     sealed: Team(int rosterId) | WinnerOf(int matchId) | LoserOf(int matchId) | Tbd
                Custom deserialiser: reads t1/t2 when it's an int; otherwise t1_from/t2_from {w|l: n};
                null with no *_from becomes Tbd.
Transaction     transactionId, type, status, leg(week), creator(userId), created, statusUpdated,
                rosterIds, consenterIds: List<Integer>,
                adds, drops: Map<String playerId, Integer rosterId>  (null → empty map),
                draftPicks: List<TradedPick>, waiverBudget: List<FaabTransfer>,
                settings: Map<String,Object> (+ waiverBid() -> OptionalInt), metadata: Map<String,Object>
FaabTransfer    sender, receiver (rosterIds), amount
TradedPick      season, round, rosterId (original owner), previousOwnerId, ownerId (current owner)
SportState      week, leg, displayWeek, season, previousSeason, leagueSeason,
                leagueCreateSeason, seasonType, seasonStartDate(LocalDate, nullable),
                seasonHasScores(Boolean; undocumented, seen in the live probe)
Draft           draftId, leagueId, type, status, sport, season, seasonType, startTime, created,
                lastPicked, settings: DraftSettings, metadata: DraftMetadata,
                draftOrder: Map<String userId, Integer slot>, slotToRosterId: Map<Integer slot, Integer rosterId>
                (slotToRosterId is only populated by GET /draft/<id>; the list endpoints leave it empty)
DraftSettings   teams, rounds, pickTimer, slotsQb, slotsRb, slotsWr, slotsTe, slotsFlex, slotsK,
                slotsDef, slotsBn (Integer) + extras: Map<String,Object> via @JsonAnySetter
                on a builder (the docs list a subset; e.g. slots_super_flex, slots_idp exist)
DraftMetadata   name, description, scoringType (raw String; values like "ppr","half_ppr","std")
DraftPick       draftId, pickNo, round, draftSlot, rosterId, playerId, pickedBy (blank → null),
                isKeeper(Boolean), metadata: DraftPickMetadata(firstName, lastName, position,
                team, number, status, injuryStatus, sport, playerId, newsUpdated)
Player          playerId, firstName, lastName, fullName (derived if absent), position,
                fantasyPositions, team (nullable: free agent), number, status, injuryStatus,
                injuryStartDate, practiceParticipation, age, height, weight, college, yearsExp,
                depthChartPosition, depthChartOrder, searchRank, birthCountry, sport, hashtag,
                externalIds: ExternalIds(espnId, yahooId, sportradarId, statsId, fantasyDataId,
                rotowireId, rotoworldId)  ← collapsed from flat fields via @JsonUnwrapped-style
                custom constructor; all String (they are mixed int/string on the wire)
TrendingPlayer  playerId, count
MatchupPair     matchupId, home: Matchup, away: Matchup (nullable)
```

**Relationships (all by ID; no object graph):**
- User 1─* League (per sport/season). League 1─* Roster, 1─* LeagueUser, 1─* Draft.
- Roster.ownerId → User.userId. Matchup.rosterId / Transaction.rosterIds / TradedPick.* → Roster.rosterId (scoped to a league).
- League.previousLeagueId → League (the dynasty chain).
- Draft.slotToRosterId joins draft slots to rosters. DraftPick.pickedBy → User.
- Every playerId → Player (from `PlayerDirectory`).

Sleeper is the source of truth for all data. The library never denormalises or persists anything, except the optional player snapshot the caller asks for.

---

## API Contracts

Every call is `GET https://api.sleeper.app/v1{path}`, unauthenticated, `Accept: application/json`, `User-Agent: jsleeper/<version>` (configurable).

### Java surface → HTTP mapping

#### Empty-result rules (from the D7 probe)

The Requester classifies every response before deserialising it:

| Response | Meaning | `Optional<T>` method | `List`/`Map` method |
|---|---|---|---|
| 2xx, body `null` or empty | Resource absent | `empty()` | empty collection |
| 2xx, JSON `[]` | No rows. This can also mean the parent ID doesn't exist; the API doesn't tell the two apart. | n/a | empty collection |
| **404, body blank or `null`** (any content type) | Resource absent | `empty()` | empty collection |
| **404, any other body** (`{"errors":{"message":"Not found."}}`, an HTML page, anything else) | **Unknown route.** That's a bug in this library or a Sleeper API change, not a missing ID. | throw `SleeperApiException(404)` | throw `SleeperApiException(404)` |

The 404 rule looks at the **body**, not the content type: Sleeper sends `application/json` for both a missing resource and an unknown route when the request asks for JSON (see D7). The body check is what keeps a broken path from silently looking like "no data". The live smoke tests (see Operational Concerns) re-run the D7 probe, so a change in either body shows up as a test failure.

| Java method | Path | Returns | Null/404 handling |
|---|---|---|---|
| `users().get(String usernameOrId)` | `/user/{usernameOrId}` | `Optional<User>` | 200+`null` → `empty()` |
| `users().leagues(String userId, Sport, int season)` | `/user/{userId}/leagues/{sport}/{season}` | `List<League>` | null → `List.of()` |
| `users().drafts(String userId, Sport, int season)` | `/user/{userId}/drafts/{sport}/{season}` | `List<Draft>` | null → `List.of()` |
| `leagues().get(String leagueId)` | `/league/{leagueId}` | `Optional<League>` | → `empty()` |
| `leagues().rosters(String leagueId)` | `/league/{leagueId}/rosters` | `List<Roster>` | → `List.of()` |
| `leagues().users(String leagueId)` | `/league/{leagueId}/users` | `List<LeagueUser>` | → `List.of()` |
| `leagues().matchups(String leagueId, int week)` | `/league/{leagueId}/matchups/{week}` | `List<Matchup>` | → `List.of()` |
| `leagues().winnersBracket(String leagueId)` | `/league/{leagueId}/winners_bracket` | `List<BracketMatch>` | → `List.of()` (no bracket before playoffs are set) |
| `leagues().losersBracket(String leagueId)` | `/league/{leagueId}/losers_bracket` | `List<BracketMatch>` | → `List.of()`. **Use `losers_bracket`**; the docs' `loses_bracket` is a typo. |
| `leagues().transactions(String leagueId, int week)` | `/league/{leagueId}/transactions/{week}` | `List<Transaction>` | → `List.of()`. The docs call the parameter `round` but it means the week. |
| `leagues().tradedPicks(String leagueId)` | `/league/{leagueId}/traded_picks` | `List<TradedPick>` | → `List.of()` |
| `leagues().drafts(String leagueId)` | `/league/{leagueId}/drafts` | `List<Draft>` (most recent first, as returned) | → `List.of()` |
| `state().get(Sport)` | `/state/{sport}` | `SportState` | 200+`null` (unsupported sport) → `SleeperApiException`, because a known `Sport` should always have a state |
| `drafts().get(String draftId)` | `/draft/{draftId}` | `Optional<Draft>` | → `empty()` |
| `drafts().picks(String draftId)` | `/draft/{draftId}/picks` | `List<DraftPick>` | → `List.of()` |
| `drafts().tradedPicks(String draftId)` | `/draft/{draftId}/traded_picks` | `List<TradedPick>` | → `List.of()` |
| `players().all(Sport)` | `/players/{sport}` | `Map<String, Player>` | ~5 MB. Javadoc warns: once a day at most; prefer `PlayerDirectory`. |
| `players().all(Sport, PlayerQuery)` | `/players/{sport}?position=&active=true` | `Map<String, Player>` | `PlayerQuery.builder().position("QB").activeOnly(true)`. Only sends `active=true`; other values are ignored server-side, so we don't model them. |
| `players().trending(Sport, TrendType)` | `/players/{sport}/trending/{type}` | `List<TrendingPlayer>` | Server defaults: 24h lookback, limit 25. `TrendType` is an enum because Sleeper answers an invalid type with `[]`, not an error. |
| `players().trending(Sport, TrendType, TrendingQuery)` | `…?lookback_hours={h}&limit={n}` | `List<TrendingPlayer>` | Validate h ≥ 1, n ≥ 1. Don't invent an upper bound. |

**Path encoding:** URL-encode every path segment (usernames are user-supplied). Reject blank IDs before any network call.

**Convenience overloads** (thin and justified by frequency of use): `leagues().matchups(leagueId)` and `leagues().transactions(leagueId)` call `state().get(NFL)` to get `displayWeek` and use it. These cost two HTTP calls and the Javadoc says so. `Season` arguments also accept `String` overloads, because responses carry season as a string.

### Error contract (all unchecked)

```
SleeperException (base; carries method + path)
├── SleeperApiException          non-2xx after retries; status(), bodySnippet()
│   ├── SleeperBadRequestException   400
│   ├── SleeperRateLimitException    429, or client-side limiter timed out before sending
│   └── SleeperServerException       500, 503 (and other 5xx)
├── SleeperTransportException    IOException / timeout / connection refused (cause preserved)
└── SleeperDeserializationException  2xx with an unparseable body; jsonPath(), bodySnippet()
```

- **404:** follows the empty-result rules above. A 404 whose body is blank or `null` means an absent resource and gives an empty result. Any other 404 body means an unknown route and throws. Rationale: a missing league is a normal outcome for callers, but a broken route is a defect and must be loud.
- **Client-side limiter timeout:** throws `SleeperRateLimitException` with `status() == 429` and `isClientSide() == true`. Nothing was sent, so callers handle it exactly like a server 429.
- **Interrupted while waiting** (rate limiter or backoff): restore the interrupt flag, then throw `SleeperTransportException` with an `InterruptedException` cause.

### Builder options

| Option | Default | Notes |
|---|---|---|
| `baseUri` | `https://api.sleeper.app/v1` | Overridden in tests to point at WireMock. |
| `httpClient` | new JDK client, HTTP/2, follow redirects NORMAL, connect timeout 5s | The caller can supply their own. |
| `requestTimeout` | 10s; `/players/{sport}` unfiltered uses 60s | Per request. |
| `rateLimit` | 900/min, burst 50, `maxWait` 30s | Value type `RateLimit.perMinute(n).burst(b).maxWait(d)`, or `RateLimit.disabled()`. 10% headroom below the documented 1000/min. One instance per client, so several clients in one JVM don't share it (see Tradeoffs). |
| `retryPolicy` | 3 attempts total; retry on 429, 500, 502, 503, 504 and `IOException`; exponential backoff 500ms × 2ⁿ, full jitter, cap 8s; honour `Retry-After` if present | Only GETs exist, so every request is idempotent and safe to retry. |
| `objectMapper` | internal tolerant mapper | If the caller supplies one, we `copy()` it and apply our required settings on top. We never mutate theirs. |
| `userAgent` | `jsleeper/<version> (+<repo url>)` | |
| `listener` | no-op | |
| `clock` | `Clock.systemUTC()` | For the rate limiter, backoff and directory TTL; makes them testable. |

---

## Usage Examples (target look and feel)

These snippets show the intended public API and act as acceptance criteria for its ergonomics. They use only the types and methods defined above. If implementation forces a signature to change, update this section too. Imports are omitted. Everything lives under `dev.brickfolio.jsleeper` and its sub-packages.

Every snippet is compiled and run against WireMock fixtures in `src/test/java/dev/brickfolio/jsleeper/examples/UsageExamplesTest.java`, which is the canonical copy. A snippet differs from the test only in the ways the test's Javadoc lists: output goes to a list, IDs come from the fixtures, and the caller's own code (`registry`, `MicrometerSleeperListener`, `render`, `ok`, `notFound`, `retryLater`, `serviceUnavailable`, `log`) is stubbed locally. When an API change breaks an example, update both in the same change.

### 1. Creating a client

```java
// Defaults: production base URL, 900 req/min limiter, 3-attempt retry, 10s timeout.
SleeperClient sleeper = SleeperClient.create();

// Or customised (instead of the line above). Build once and share; the client is thread-safe.
SleeperClient sleeper = SleeperClient.builder()
        .userAgent("brickfolio-dashboard/1.0")
        .requestTimeout(Duration.ofSeconds(5))
        .rateLimit(RateLimit.perMinute(600).burst(20).maxWait(Duration.ofSeconds(10)))
        .retryPolicy(RetryPolicy.builder()
                .maxAttempts(4)
                .initialBackoff(Duration.ofMillis(250))
                .maxBackoff(Duration.ofSeconds(5))
                .build())
        .listener(new MicrometerSleeperListener(registry))   // caller's own RequestListener
        .build();
```

### 2. Username → this season's leagues

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

### 3. Standings

```java
String leagueId = "1048301234567890123";

Map<String, LeagueUser> usersById = sleeper.leagues().users(leagueId).stream()
        .collect(Collectors.toMap(LeagueUser::userId, Function.identity()));

List<Roster> standings = sleeper.leagues().rosters(leagueId).stream()
        .sorted(Comparator
                .comparing((Roster r) -> r.settings().wins()).reversed()
                .thenComparing(r -> r.settings().pointsFor(), Comparator.reverseOrder()))
        .toList();

for (Roster r : standings) {
    LeagueUser owner = usersById.get(r.ownerId());          // null if the team is orphaned
    String team = owner == null ? "(orphan)"
            : owner.teamName().orElse(owner.displayName());
    RosterSettings s = r.settings();
    System.out.printf("%-24s %d-%d-%d  PF %s  PA %s%n",
            team, s.wins(), s.losses(), s.ties(), s.pointsFor(), s.pointsAgainst());
}
```

### 4. Weekly scoreboard with player names

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
        double pts = home.playersPoints().getOrDefault(playerId, 0.0);
        System.out.printf("    %-22s %6.2f%n", name, pts);
    }
}
```

### 5. Transactions for a week

```java
for (Transaction tx : sleeper.leagues().transactions(leagueId, week)) {
    String summary = switch (tx.type()) {
        case TRADE      -> "Trade between rosters " + tx.rosterIds();
        case WAIVER     -> "Waiver claim" + tx.waiverBid().stream()
                                .mapToObj(bid -> " ($" + bid + " FAAB)").findFirst().orElse("");
        case FREE_AGENT -> "Free-agent move";
        case UNKNOWN    -> "Unrecognised type: " + tx.typeRaw();
    };
    System.out.printf("[%s] %s (%s)%n", tx.created(), summary, tx.status());

    tx.adds().forEach((playerId, rosterId) ->
            System.out.printf("   + %s → roster %d%n", playerId, rosterId));
    tx.drops().forEach((playerId, rosterId) ->
            System.out.printf("   - %s ← roster %d%n", playerId, rosterId));
    tx.draftPicks().forEach(p ->
            System.out.printf("   pick %s R%d: roster %d → %d%n",
                    p.season(), p.round(), p.previousOwnerId(), p.ownerId()));
}
```

### 6. Playoff bracket (sealed `BracketSlot`, exhaustive switch)

```java
static String describe(BracketSlot slot) {
    return switch (slot) {
        case BracketSlot.Team(int rosterId)   -> "Roster " + rosterId;
        case BracketSlot.WinnerOf(int match)  -> "Winner of M" + match;
        case BracketSlot.LoserOf(int match)   -> "Loser of M" + match;
        case BracketSlot.Tbd()                -> "TBD";
    };
}

for (BracketMatch m : sleeper.leagues().winnersBracket(leagueId)) {
    System.out.printf("R%d M%d: %s vs %s%s%n",
            m.round(), m.matchId(), describe(m.team1()), describe(m.team2()),
            m.winner() == null ? "" : "  → winner roster " + m.winner());
}
```

### 7. Draft board

```java
League league = sleeper.leagues().get(leagueId).orElseThrow();
Draft draft = sleeper.drafts().get(league.draftId()).orElseThrow();  // single GET fills slotToRosterId

System.out.printf("%s draft, %d rounds, %s%n",
        draft.type(), draft.settings().rounds(), draft.metadata().scoringType());

for (DraftPick pick : sleeper.drafts().picks(draft.draftId())) {
    DraftPickMetadata md = pick.metadata();
    System.out.printf("%3d. (R%d.%02d) %s %s, %s %s%s%n",
            pick.pickNo(), pick.round(), pick.draftSlot(),
            md.firstName(), md.lastName(), md.position(), md.team(),
            Boolean.TRUE.equals(pick.isKeeper()) ? "  [K]" : "");
}
```

### 8. Trending adds

```java
List<TrendingPlayer> hot = sleeper.players().trending(Sport.NFL, TrendType.ADD,
        TrendingQuery.builder().lookbackHours(48).limit(10).build());

hot.forEach(t -> System.out.printf("%-22s +%,d%n",
        players.get(t.playerId()).map(Player::fullName).orElse(t.playerId()), t.count()));
// Attribution to Sleeper is required wherever this data is displayed (see README).
```

### 9. Fetching a league concurrently (virtual threads)

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    Future<League>           league   = executor.submit(() -> sleeper.leagues().get(leagueId).orElseThrow());
    Future<List<Roster>>     rosters  = executor.submit(() -> sleeper.leagues().rosters(leagueId));
    Future<List<LeagueUser>> users    = executor.submit(() -> sleeper.leagues().users(leagueId));
    Future<List<Matchup>>    matchups = executor.submit(() -> sleeper.leagues().matchups(leagueId, week));

    render(league.get(), rosters.get(), users.get(), matchups.get());
}
// All four calls share the client's rate limiter, so concurrency can't push past the limit.
```

### 10. Error handling

```java
try {
    Optional<League> maybe = sleeper.leagues().get(leagueId);
    if (maybe.isEmpty()) {
        // Absent ID: 404+null or 200+null from Sleeper. Normal outcome, no exception.
        return notFound();
    }
    return ok(maybe.get());
} catch (SleeperRateLimitException e) {
    // 429 after retries, or the local limiter couldn't get a token within maxWait.
    return retryLater(Duration.ofMinutes(1));
} catch (SleeperServerException e) {
    // 5xx after retries; e.status() is 500/503/…
    return serviceUnavailable();
} catch (SleeperApiException e) {
    // Any other non-2xx, including a 404 with an error body or HTML (unknown route, which is a bug or an API change).
    log.error("Sleeper {} {} -> {}: {}", e.method(), e.path(), e.status(), e.bodySnippet());
    throw e;
} catch (SleeperTransportException | SleeperDeserializationException e) {
    log.error("Sleeper call failed", e);
    throw e;
}
```

### 11. Persisting the player snapshot across restarts

```java
Path snapshot = Path.of("data/players-nfl.json");

if (Files.exists(snapshot)) {
    try (InputStream in = Files.newInputStream(snapshot)) {
        players.loadSnapshot(in);        // keeps the stored loadedAt; no HTTP if < 24h old
    }
}

Player mahomes = players.get("4046").orElseThrow();   // refreshes only if the TTL has expired
System.out.println(mahomes.fullName() + " " + mahomes.team() + " " + mahomes.externalIds().espnId());

try (OutputStream out = Files.newOutputStream(snapshot)) {
    players.writeSnapshot(out);
}
```

### 12. Avatars

```java
URI full  = Avatars.full(user.avatar());    // https://sleepercdn.com/avatars/<id>
URI thumb = Avatars.thumb(user.avatar());   // https://sleepercdn.com/avatars/thumbs/<id>
```

---

## Critical Path Walkthrough

### Flow 1: "Show me my leagues this season" (username → leagues → league view)

1. `client.users().get("someuser")`. Requester: limiter takes a token (instant at low volume) → GET `/user/someuser` → 200 + JSON → `Optional<User>`.
   - *Error path:* body is `null` (unknown username) → `Optional.empty()`; the caller shows "user not found". On 503: back off ~0.5s then ~1s; if the third attempt fails, throw `SleeperServerException(503)`.
2. `client.state().get(NFL)` → `leagueSeason` (for example `"2026"`). Use `leagueSeason`, not `season`: around December, `league_create_season` flips ahead of the current season.
3. `client.users().leagues(user.userId(), NFL, 2026)` → `List<League>`. An empty list means no leagues.
4. For the chosen league: `leagues().rosters(id)` and `leagues().users(id)`. The caller joins `Roster.ownerId` to `LeagueUser.userId` and uses `Avatars.thumb(avatar)`.
- **Calls:** 5. **Latency budget:** about 100–250 ms per call in practice, so 0.5–1.2 s sequential. Steps 4a and 4b are independent; callers may run them in parallel because the client is thread-safe. Nothing here comes near the rate limit.

### Flow 2: Weekly scoreboard

1. `state().get(NFL)` → `displayWeek` (the week the UI shows; it can differ from `week` during the off-season or on week boundaries).
2. `leagues().matchups(leagueId, displayWeek)` → 12 `Matchup` rows (for a 12-team league).
3. `Matchups.pair(rows)` → 6 `MatchupPair`s grouped by `matchupId`. Rows with a null `matchupId` become single-team pairs.
4. Show `customPoints` if it's non-null, otherwise `points`. A `Matchup.effectivePoints()` helper encodes that rule.
5. Resolve starter IDs to names through `PlayerDirectory.get(id)`. That is a memory lookup, with no HTTP call after the first daily load.
- *Error path:* if the week is out of range (for example 19), Sleeper returns `[]` → an empty list. The library doesn't validate weeks against the schedule, only that `week ≥ 1`.
- **Calls:** 2, plus a cold-start player load. **Budget:** under 500 ms warm.

### Flow 3: Player directory load and refresh

1. `PlayerDirectory dir = PlayerDirectory.builder(client).sport(NFL).ttl(Duration.ofHours(24)).build();` builds without making any network call (lazy).
2. The first `dir.get("4046")` takes the directory's lock (only one refresh at a time; other threads wait). It then calls `players().all(NFL)` with a 60s timeout: about 5 MB streamed into Jackson, then ~10–12k `Player` records. Expect 1–3 s and roughly 30–60 MB of transient heap, with about 15–25 MB retained.
3. It swaps in an immutable `Map` through a `volatile` reference and records `loadedAt = clock.instant()`. Readers never block once data exists.
4. Calls made after `loadedAt + ttl` trigger one refresh. Until it finishes, other threads keep reading the old map: the refresh runs on the calling thread, and the others see stale data, not a block.
- *Error path:* if the refresh throws and an old map exists, keep the old map, log a WARNING and push `loadedAt` forward by `min(ttl, 1h)` so a failing endpoint isn't hit on every call. If no map exists, rethrow.
- *Persistence:* `writeSnapshot(out)` writes `{loadedAt, sport, players}` as JSON. `loadSnapshot(in)` restores it and keeps the stored `loadedAt`, so a process restart within 24h makes no new call. This covers Sleeper's advice to "save the information on your own servers" without the library choosing a storage location.

### Flow 4: Single request internals (used by every call)

`facade` → validate args → build URI → `Requester.get`:
1. `rateLimiter.acquire()`. Block until a token is free; give up at `maxWait` → `SleeperRateLimitException`.
2. `listener.onRequest`, then `httpClient.send(req, BodyHandlers.ofInputStream())`.
3. Status 200: peek the first non-whitespace byte. If the body is `null` or empty → empty result. Otherwise stream it into `mapper.readValue(stream, javaType)`.
4. Status 404: if the body is blank or `null`, return the empty result. Otherwise throw `SleeperApiException(404)` (unknown route). Retryable status or `IOException`: compute the backoff (or `Retry-After`), sleep, go back to step 1 (a retry also takes a rate-limit token). Any other status: throw the mapped exception.
5. `listener.onResponse` runs on every attempt, with the status or exception.

---

## Tradeoff Log

| Decision | Options Considered | Choice | Rationale | Risks Accepted |
|---|---|---|---|---|
| HTTP stack | JDK `HttpClient`; OkHttp; Retrofit; Apache HC5 | **JDK `HttpClient`** | Zero dependencies, HTTP/2, thread-safe, good enough for 17 GETs. Retrofit's interface-generation pays off at 100+ endpoints, not 17. | We hand-write retries and the rate limiter, about 150 lines. No connection-pool tuning knobs. |
| JSON library | Jackson; Gson; Moshi; JSON-B | **Jackson** | Best record support, lenient enum and coercion settings, streaming for the 5 MB payload, and the most common choice among consumers. | Jackson CVE churn is ours to track. Its version may conflict with the consumer's; we keep a lower bound only, no shading. |
| Sync vs async | Sync only; async only (`CompletableFuture`); both | **Sync only** | Simplest to use, test and debug. Thread-safe so callers can parallelise with virtual threads (always available on the Java 25 baseline) or executors. The rate limiter and `PlayerDirectory` lock must use `ReentrantLock`, not `synchronized`, so they never pin virtual threads. Two API surfaces would double the tests and docs. | Reactive users wrap calls themselves. An async facade can come later without breaking changes (Future Work). |
| Models | Records; Lombok/Immutables; mutable POJOs; raw `JsonNode` | **Records + tolerant mapping, loose `Map` for undocumented objects** | Immutable, no annotation processor. A loose map is honest about what the docs don't specify. | Callers read `settings.get("playoff_week_start")` untyped. If Sleeper renames a documented field, the value becomes null instead of failing (we prefer that over a crash). |
| Unknown enum values | Fail; String fields; open enum with UNKNOWN + raw string | **Open enum + raw** | Type safety for known values. Never crashes on new ones. | Two accessors per enum field. |
| Not-found semantics | Throw `NotFoundException`; return null; `Optional`/empty list | **`Optional` / empty list, with a 404 split by body** | Sleeper mixes 200+`null`, 200+`[]` and 404+`null` for missing IDs (D7). A single empty contract hides that mess from callers. Treating only a blank or `null` 404 body as absent keeps route bugs loud. | A typo in a league ID looks the same as "no data". The Javadoc spells this out. We depend on Sleeper continuing to answer a missing resource with a `null` body and an unknown route with something else; the live smoke tests watch for that. |
| Rate limiting | None (document only); global static limiter; per-client limiter | **Per-client token bucket, 900/min default** | An IP block is a severe, opaque failure. Prevention is cheap. Per-client limiters avoid hidden global state. | Several clients in one JVM, or several JVMs on one IP, can exceed the limit together. The docs recommend one client per process, and the limit is configurable. |
| Retry | None; fixed; exponential + jitter | **Exponential + full jitter, 3 attempts** | Every call is an idempotent GET. Jitter avoids synchronised retry bursts. | It adds up to about 12s of worst-case latency before a failure surfaces. Callers can set `RetryPolicy.none()`. |
| Player caching | Inside the client (implicit); separate opt-in `PlayerDirectory`; none | **Opt-in `PlayerDirectory`, in-memory + caller-owned snapshot streams** | Keeps the client a plain, predictable 1:1 mapping, and makes the cache's cost and lifecycle visible. Caller-owned streams avoid choosing a file path or database. | Callers who skip it and call `players().all()` in a loop break Sleeper's guidance. Mitigated with Javadoc and a one-time WARN log if the unfiltered call runs more than once per hour per client. |
| Bracket `t1`/`t2` polymorphism | Raw `Object`; two fields; sealed `BracketSlot` | **Sealed `BracketSlot` + custom deserialiser** | Exhaustive pattern-matching `switch` on Java 25 with record deconstruction (`case WinnerOf(int m) -> …`). It makes the "winner of match 1" semantics explicit. | One custom deserialiser to maintain. |
| Player external IDs | Flat fields; nested `ExternalIds` | **Nested, all as `String`** | They're mixed int/string on the wire and only matter for cross-site joins. Grouping keeps `Player` readable. | Departs from the 1:1 wire shape. Documented. |
| Matchups helper and week-defaulting overloads | Leave to callers; include | **Include (tiny)** | Every consumer writes the pairing code. Getting `displayWeek` vs `week` vs `leg` wrong is a common bug. | Small scope creep. These are kept strictly stateless. |
| Build tool | Gradle; Maven | **Gradle KTS, ≥ 9.1** (D2) | Confirmed choice. 9.1 is the first Gradle that runs on JDK 25. | Consumers stuck on JDK < 25 can't use the library. Accepted for a hobby project. |

---

## Operational Concerns

This is a library, so "operations" means its behaviour inside consumer processes, plus release engineering.

**Observability (in the library)**
- `RequestListener` events: path template (for example `/league/{id}/matchups/{week}`, so labels stay low-cardinality), status, attempt, duration, response bytes and retry reason. This is enough for consumers to wire Micrometer and alert on error rate or 429s. We don't depend on Micrometer ourselves.
- `System.Logger`: DEBUG per request, INFO per retry, WARNING for directory stale-serve, repeated unfiltered player loads, and limiter waits over 5s.

**Testing strategy (part of "done")**
- **Fixtures:** one recorded JSON response per endpoint under `src/test/resources/fixtures/`, captured from the live API, not copied from the docs, because the doc examples contain `//` comments and trailing commas. Include edge fixtures: a `null` body, a bracket with `t1_from`/`t2_from`, a transaction with FAAB `waiver_budget`, a draft pick with a blank `picked_by`, a matchup with null `matchup_id` and `custom_points`, and players with team-code IDs.
- **Unit tests:** deserialisation of every model against the fixtures; unknown enum value → `UNKNOWN` with the raw string kept; an extra unknown field is ignored.
- **WireMock tests:** retry and backoff (429 then 200; three 503s → exception), `Retry-After`, 404+`null` → empty, 404 with an empty body → empty, **404 with an error object or HTML → `SleeperApiException`**, 200+`null` → empty, 200+`[]` → empty list, timeout → `SleeperTransportException`, and path encoding of usernames with spaces or unicode.
- **Rate limiter and directory:** deterministic tests with a fake `Clock` and an injectable sleeper.
- **Live smoke tests:** tagged `@Tag("live")` and excluded by default. Run nightly in CI against a known public league ID (configured as a CI variable), with about 20 calls per run. They catch schema drift early. They also re-run the D7 probe (nonexistent IDs) and assert the documented status/content-type for each endpoint, so a Sleeper change to its not-found behaviour fails the build instead of silently changing results.

**CI and release**
- GitHub Actions: build + unit + WireMock tests on JDK 25 (Temurin); a nightly live-smoke job that opens an issue on failure.
- Semver. Model field additions are minor versions. Any removal or type change is major. Publish to Maven Central via the Gradle `maven-publish` and signing plugins, with sources and Javadoc jars.
- **Rollback:** consumers pin the previous version. A broken release is fixed with a patch release, never by re-publishing the same version (Central is immutable).

**Capacity and footprint**
- Typical sync of one league for one week: about 6 calls (state, league, rosters, users, matchups, transactions). At the 900/min default, one client can fully sync about 150 leagues a minute. That is plenty for a hobby app and leaves a clear 10x path by sharding across IPs or processes (out of scope).
- Player map: about 5 MB over the wire, ~15–25 MB retained heap, one fetch a day.
- The library holds no threads of its own. The JDK `HttpClient` uses its default executor unless the caller provides one.

---

## Implementation Plan (increments)

The build is split into 24 small increments. Each one is a single PR that:
- leaves `./gradlew build` green;
- ships its own tests;
- adds no unused code ahead of need, so no stubs for later increments;
- is reviewable in one sitting (roughly 50–400 lines of production code).

Increments run in order unless the "Depends on" column says otherwise. Increments 6–14 (the endpoint slices) only depend on 4 and 5 and can be done in any order or in parallel.

**Sizes:** S ≈ under 150 lines of production code, M ≈ 150–400.

### Overview

| # | Increment | Size | Depends on |
|---|---|---|---|
| 0 | Project scaffold and CI | S | — |
| 1 | Exception hierarchy | S | 0 |
| 2 | `JsonCodec` (mapper config, open enums, timestamps) | S | 0 |
| 3 | `Requester`, basic version (GET, empty-result rules, deserialisation) | M | 1, 2 |
| 4 | `SleeperClient` + builder + `StateApi` (first vertical slice) | M | 3 |
| 5 | Fixture recording script | S | 0 |
| 6 | Users: `users().get`, `Avatars` | S | 4, 5 |
| 7 | Leagues: `leagues().get`, `users().leagues`, `leagues().users` | M | 4, 5 |
| 8 | Rosters + standings maths | S | 7 |
| 9 | Matchups + `Matchups.pair` | S | 7 |
| 10 | Playoff brackets (`BracketSlot`) | M | 7 |
| 11 | Transactions + league traded picks | M | 7 |
| 12 | Drafts: `drafts().get`, `users().drafts`, `leagues().drafts` | M | 4, 5 |
| 13 | Draft picks + draft traded picks | S | 12, 11 (reuses `TradedPick`) |
| 14 | Players: `players().all`, `PlayerQuery`, trending | M | 4, 5 |
| 15 | `RetryPolicy` + backoff | M | 3 |
| 16 | `RateLimiter` + `RateLimit` | M | 3 |
| 17 | `RequestListener` + logging | S | 15, 16 |
| 18 | Convenience overloads (default week, `String` season) | S | 7, 9, 11 |
| 19 | `PlayerDirectory`: TTL cache and refresh | M | 14 |
| 20 | `PlayerDirectory`: snapshots | S | 19 |
| 21 | Usage examples as compiled tests | S | 6–20 |
| 22 | Live smoke tests + nightly CI (with the docs check and D7 probe) | M | 6–14 |
| 23 | README, Javadoc, Central publishing | M | all |

### Increment details

**0. Project scaffold and CI** (S)
- Gradle wrapper ≥ 9.1, `settings.gradle.kts`, `build.gradle.kts` with the `java-library` plugin and toolchain 25, and `gradle/libs.versions.toml` (Jackson, JUnit 5, AssertJ, WireMock).
- `module-info.java` declaring `dev.brickfolio.jsleeper`, with dependencies on `java.net.http` and Jackson.
- An empty `dev.brickfolio.jsleeper` package with `package-info.java`.
- `.github/workflows/build.yml` running `./gradlew build` on Temurin 25.
- **Done when:** CI is green on a PR. `./gradlew build` produces a jar with a module descriptor.

**1. Exception hierarchy** (S)
- `error/`: `SleeperException`, `SleeperApiException`, `SleeperBadRequestException`, `SleeperRateLimitException` (with `isClientSide()`), `SleeperServerException`, `SleeperTransportException` and `SleeperDeserializationException`.
- Each carries method and path, plus status, body snippet and JSON path as the error contract specifies.
- A static factory `SleeperApiException.forStatus(method, path, status, body)` picks the right subclass. The body snippet is truncated to 512 bytes.
- **Done when:** unit tests cover status → subclass mapping (400, 429, 500, 503, 418 → base) and snippet truncation.

**2. `JsonCodec`** (S)
- `internal/JsonCodec`: builds the `ObjectMapper` according to the global mapping rules: snake_case naming, ignore unknown properties, read unknown enum values as their default, `JavaTimeModule` with millisecond timestamps, scalar coercion on.
- `copyOf(ObjectMapper)` takes a caller's mapper, copies it and applies the required settings on top.
- Establish the **open-enum pattern** once: a `WireEnum` interface with `wireValue()`, plus `@JsonEnumDefaultValue UNKNOWN`. Write the convention down in `package-info`.
- **Done when:** tests use small test-only records to check snake_case, ignored extra fields, an unknown enum value becoming `UNKNOWN`, epoch ms becoming `Instant`, `"10"` becoming `Integer 10`, and that the caller's mapper is left unmodified.

**3. `Requester`, basic version** (M)
- `internal/Requester.get(path, query, JavaType, EmptyKind)` with `EmptyKind` = `OPTIONAL | LIST | MAP | REQUIRED`.
- Builds the URI with path-segment encoding, sends through `HttpClient` (`BodyHandlers.ofInputStream`), and applies the **empty-result rules** table: 2xx `null`/empty body, 404 with a blank or `null` body, 404 with any other body.
- Streams the body into Jackson. Wraps `IOException` as `SleeperTransportException` and parse failures as `SleeperDeserializationException`, keeping the first 512 bytes of the body.
- No retries, rate limiting or listeners yet.
- **Done when:** WireMock tests cover every row of the D7 table (200+`null`, 200+`[]`, 404+`null`, 404 with an error object or HTML → throws, 500 → `SleeperServerException`, malformed JSON, connection refused, timeout), plus path encoding of `"some user/ü"`.

**4. `SleeperClient` + builder + `StateApi`** (M). This is the first vertical slice.
- `SleeperClient.create()` and `builder()` with these options only: `baseUri`, `httpClient`, `requestTimeout`, `userAgent`, `objectMapper`, `clock`. Validates its configuration in `build()`.
- `model/`: the `Sport` and `SeasonType` enums, and the `SportState` record (including `seasonHasScores`).
- `api/StateApi.get(Sport)` with `REQUIRED` empty handling.
- **Done when:** a WireMock test round-trips `/state/nfl`; a 200+`null` throws `SleeperApiException`; `User-Agent` and `Accept` headers are sent; a blank or invalid `baseUri` fails at `build()`.

**5. Fixture recording script** (S)
- `scripts/record-fixtures.sh` takes `USER_ID`, `LEAGUE_ID`, `DRAFT_ID`, `WEEK` and `SEASON` from environment variables or flags. It curls each endpoint, pretty-prints the JSON, and writes it to `src/test/resources/fixtures/<endpoint>.json`.
- The full player list is cut down to a small sample of about 20 players, including one team defense and one free agent with a null `team`, so the repo doesn't hold a 5 MB file.
- A README note says which public league the fixtures came from. Re-run the script to refresh them.
- **Done when:** the script produces fixtures for all 17 routes. Edge-case fixtures (bracket with `*_from`, FAAB waiver, blank `picked_by`, null `matchup_id`) are hand-trimmed from real responses and checked in.
- *Needs from you:* a public league ID (ideally a dynasty league with a finished season, FAAB and playoffs) and its draft ID.

**6. Users** (S)
- The `User` record, `UsersApi.get(usernameOrId)` returning `Optional`, and `util/Avatars` with `full` and `thumb`.
- **Done when:** fixture deserialisation works; an unknown user gives `Optional.empty()` (WireMock 200+`null`); `Avatars` builds the documented URLs and rejects blank IDs.

**7. Leagues** (M)
- The `League` record and `LeagueStatus` enum. Loosely typed `settings` and `scoringSettings`. `rosterPositions`.
- The `LeagueUser` record with `teamName()`.
- `LeaguesApi.get` (`Optional`), `LeaguesApi.users`, and `UsersApi.leagues(userId, Sport, int season)`.
- **Done when:** fixture tests pass; a 404 with a `null` body on `get` gives `empty()`; an empty `[]` gives an empty list; `teamName()` handles missing metadata.

**8. Rosters** (S)
- The `Roster` and `RosterSettings` records, `pointsFor()` and `pointsAgainst()` as `BigDecimal` with scale 2 (D6), and `LeaguesApi.rosters`.
- **Done when:** fixture tests pass; `1617` + `78` gives `1617.78`; a null decimal is treated as 0; a null `ownerId` (orphaned team) is tolerated.

**9. Matchups** (S)
- The `Matchup` record (`playersPoints` and `startersPoints` only if the fixtures from increment 5 confirm them), `effectivePoints()`, `bench()`, `LeaguesApi.matchups(leagueId, week)`, and `util/Matchups.pair` returning `MatchupPair`.
- **Done when:** a 12-row fixture gives 6 pairs; a null `matchupId` gives a pair with `away == null`; `customPoints` overrides `points`; `week < 1` throws `IllegalArgumentException` before any HTTP call.

**10. Playoff brackets** (M)
- `model/bracket/BracketMatch` and the sealed `BracketSlot` (`Team`, `WinnerOf`, `LoserOf`, `Tbd`), with a custom deserialiser that reads the `t1`/`t1_from` and `t2`/`t2_from` pairs. Also `LeaguesApi.winnersBracket` and `losersBracket`, using the `losers_bracket` path.
- **Done when:** the docs' 7-row example (converted to valid JSON) and the live fixture both deserialise; every slot variant is covered; `p` maps to `placement`; an unexpected shape (for example `{x:1}`) gives `SleeperDeserializationException`.

**11. Transactions + league traded picks** (M)
- The `Transaction`, `TransactionType`, `TransactionStatus`, `FaabTransfer` and `TradedPick` records and enums. `waiverBid()`. Null `adds`/`drops` become empty maps.
- `LeaguesApi.transactions(leagueId, week)` and `LeaguesApi.tradedPicks`.
- **Done when:** trade, free-agent and FAAB-waiver fixtures deserialise; `waiverBid()` reads `settings.waiver_bid`; the raw string is kept for an `UNKNOWN` type.

**12. Drafts** (M)
- The `Draft`, `DraftType`, `DraftStatus`, `DraftSettings` (with `extras` through `@JsonAnySetter`) and `DraftMetadata` records and enums.
- `DraftsApi.get` (`Optional`), `UsersApi.drafts` and `LeaguesApi.drafts`.
- **Done when:** fixture tests pass; `slotToRosterId` is filled from the single-draft endpoint and empty from the list endpoints; `slots_super_flex` lands in `extras`; a null `draft_order` becomes an empty map.

**13. Draft picks + draft traded picks** (S)
- The `DraftPick` and `DraftPickMetadata` records. A blank `picked_by` becomes null; a string `roster_id` is coerced.
- `DraftsApi.picks` and `DraftsApi.tradedPicks` (reusing `TradedPick`).
- **Done when:** fixture tests pass, including a pick with no `roster_id`, `round` or `draft_slot` (the docs' third example). A 404 with a `null` body gives an empty list.

**14. Players** (M)
- The `Player` record with nested `ExternalIds` (all `String`; numbers coerced) and a derived `fullName()`. `TrendType`, `TrendingPlayer`, and the `PlayerQuery` and `TrendingQuery` builders.
- `PlayersApi.all(Sport[, PlayerQuery])` using the 60-second timeout for unfiltered calls, and `PlayersApi.trending(...)`.
- **Done when:** the sampled fixture deserialises into `Map<String, Player>`; query strings are right (`active=true` only sent when requested; `lookback_hours` and `limit` validated ≥ 1); a team-defense ID such as `"DET"` works.

**15. `RetryPolicy`** (M)
- `RetryPolicy.defaults()`, `none()` and `builder()`. Exponential backoff with full jitter and a cap. `Retry-After` parsing (seconds or HTTP date).
- Wired into the `Requester` loop.
- Package-private `Sleeper` and `Random` seams so tests run without real waiting.
- If the thread is interrupted while waiting, it restores the interrupt flag and throws `SleeperTransportException`.
- **Done when:** WireMock tests pass for 429 → 200 (success on attempt 2), three 503s (throws after 3 attempts), `Retry-After: 2` honoured, 400 not retried, `IOException` retried; backoff values are checked with a seeded `Random`.

**16. `RateLimiter`** (M)
- A token bucket built on `Clock` + `ReentrantLock`, with no `synchronized` (D1). Value type `RateLimit.perMinute(n).burst(b).maxWait(d)` and `RateLimit.disabled()`.
- Called before every attempt, including retries. A `maxWait` timeout throws a client-side `SleeperRateLimitException`.
- **Done when:** fake-clock tests pass for burst exhaustion, refill rate, the `maxWait` timeout and the disabled mode. A concurrency test runs 200 virtual threads and checks no more than the configured number of permits are handed out in any window.

**17. `RequestListener` + logging** (S)
- The `RequestListener`, `RequestEvent` and `ResponseEvent` types (path *template*, status, attempt, duration, bytes, retry reason). Listener exceptions are caught.
- `System.Logger` output at the levels set out in Component Diagram → Logging.
- The one-time WARN when the unfiltered player list is fetched more than once an hour on the same client.
- **Done when:** a recording listener sees one event per attempt with the right template; a throwing listener doesn't fail the call; the WARN fires once.

**18. Convenience overloads** (S)
- `leagues().matchups(leagueId)` and `leagues().transactions(leagueId)` using `state().get(NFL).displayWeek()`.
- `String season` overloads on `users().leagues` and `users().drafts`.
- **Done when:** a WireMock test confirms the two-call sequence and that the week comes from `display_week`, not `week`.

**19. `PlayerDirectory`: cache and refresh** (M)
- Builder (`sport`, `ttl`); lazy first load; a `volatile` immutable map; a `ReentrantLock` so only one thread refreshes at a time.
- While a refresh runs, other threads read the old data. If a refresh fails, keep the old data and wait `min(ttl, 1h)` before trying again.
- `get(id)`, `all()` and `refresh()`.
- **Done when:** fake-clock tests cover lazy load, TTL expiry, the single refresher under concurrency (one HTTP call while 50 threads call `get`), stale-on-error, and rethrow when there's no data at all.

**20. `PlayerDirectory`: snapshots** (S)
- `writeSnapshot(OutputStream)` and `loadSnapshot(InputStream)` using the format `{loadedAt, sport, players}`. A sport mismatch throws `IllegalArgumentException`.
- **Done when:** a round-trip test passes; loading a snapshot under 24 hours old makes no HTTP call; loading an expired one triggers a refresh on the next `get`.

**21. Usage examples as compiled tests** (S)
- Copy the 12 snippets from **Usage Examples** into `src/test/java/.../examples/UsageExamplesTest.java` and run them against WireMock with the fixtures.
- **Done when:** they compile and pass. Any API change that breaks an example must update this spec section in the same PR.

**22. Live smoke tests + nightly CI** (M)
- JUnit `@Tag("live")` tests are excluded from `build` and run with `./gradlew liveTest`. They call each endpoint once against the configured public IDs (about 20 calls) and re-assert the D7 status and content-type table.
- `.github/workflows/nightly.yml` runs `liveTest` and `scripts/check-sleeper-docs.sh`, and opens an issue on test failure or on docs-check exit code 20.
- **Done when:** a manual run of the nightly workflow is green, and a deliberately wrong baseline hash opens an issue.

**23. README, Javadoc and publishing** (M)
- README: quick start (taken from the Usage Examples), the non-commercial notice, trending-data attribution, and the "store `user_id`, not username" and "players once a day" guidance.
- Javadoc on every public type, with `-Xdoclint` in CI.
- `maven-publish` + `signing` + Central Portal upload for `dev.brickfolio:jsleeper`, with sources and Javadoc jars. Version `0.1.0`.
- **Done when:** a dry-run publish to a local repository produces a valid POM. Central namespace verification for `brickfolio.dev` is complete (D3). Tag `v0.1.0` publishes.

### Milestones

| Milestone | After increment | What a caller can do |
|---|---|---|
| **M1: Walking skeleton** | 4 | Create a client and read the NFL state. |
| **M2: Read-only coverage** | 14 | Call all 17 routes with typed results (no retries or limiting yet; fine for local use). |
| **M3: Production-safe** | 17 | Retries, rate limiting and metrics hooks. Safe to run unattended. |
| **M4: Feature-complete** | 21 | Convenience overloads, `PlayerDirectory`, all examples verified. |
| **M5: Released** | 23 | `0.1.0` on Maven Central, with nightly drift detection. |

---

## Out of Scope / Future Work

| Item | Why deferred |
|---|---|
| Async (`CompletableFuture`) facade | Sync plus virtual threads covers the need. Can be added later without breaking changes by sharing `Requester`. |
| Aggregate views (`LeagueSnapshot` that joins rosters, users and players; standings computation; dynasty history walking `previousLeagueId`) | Opinionated. Better built after real usage shows which joins people need. |
| Disk or database player cache | The caller owns storage through snapshot streams. We avoid picking file paths or databases. |
| Typed `LeagueSettings` / `ScoringSettings` | The docs don't define them. Add typed views once fixtures show a stable shape. |
| HTTP response caching (ETag, Cache-Control) | Sleeper documents no cache headers. Revisit if the live API turns out to send them. |
| Undocumented endpoints (GraphQL, projections, stats, `/schedule`) | Not part of the public contract. They could change without notice and the license terms are unclear. |
| Write operations | The API is read-only. |
| Non-NFL sports beyond the path parameter | The docs support only NFL for leagues and drafts. The open enums keep the door open. |
| Cross-process / cross-IP rate coordination | Needs shared state (Redis or similar). That's the 100x scale, not the current one. |
| Kotlin/Scala-specific extensions | Records and `Optional` work acceptably from both. |

---

## Appendix A: Sleeper API Reference Baseline (as documented)

This appendix records **exactly what https://docs.sleeper.com/ said** when this spec was written. It covers only what the docs state: no observed behaviour, no inferences, no library decisions. Those live in the sections above, and D7 records the live-probe findings. Sleeper doesn't version its docs, so this baseline is how we detect changes later: re-fetch the page, compare it with the fingerprint, and if it differs, diff it section by section against the tables below.

Conventions used in this appendix:
- *Doc comment* is the inline `//` comment, or the description text, exactly as the docs wrote it, typos included.
- *Type* is the JSON type of the example value. The docs only state types explicitly for the bracket table; everywhere else the type is read from the example.
- *Example* is the value shown in the docs' example response.
- Field order follows the docs' examples.

### A.0 Snapshot fingerprint

| Item | Value |
|---|---|
| URL | `https://docs.sleeper.com/` (a single page; no sub-pages) |
| Fetched | 2026-11-26 22:20:10 GMT |
| HTTP `Last-Modified` | `Wed, 21 Oct 2026 20:47:40 GMT` |
| HTML size | 84,121 bytes |
| Normalised-HTML SHA-256 | `bc5e8afe21f49fecf29280518612eaf8f39b6d00f19340dc2b5e7dfc2be8feef` (Cloudflare email tokens blanked; see A.10) |
| Extracted-text size | 25,073 bytes, 773 non-blank lines |
| Extracted-text SHA-256 | `4bf713cd298ea413028b577a8fc4a4fbb1fda6570589707ded5250dc18ee97a8` |
| Nav sections (in order) | Introduction · User · Avatars · Leagues (Get all leagues for user, Get a specific league, Getting rosters in a league, Getting users in a league, Getting matchups in a league, Getting the playoff bracket, Get transactions, Get traded picks, Get NFL state) · Drafts (Get all drafts for user, Get all drafts for a league, Get a specific draft, Get all picks in a draft, Get traded picks in a draft) · Players (Fetch all players, Trending Players) · Errors |

The normalised-HTML hash changes with any cosmetic or asset change, so it is a quick "did anything change?" check. The extracted-text hash only changes when the content changes. Compare both: if only the HTML hash differs, the change is almost certainly cosmetic. **The raw HTML is not hashed**, because Cloudflare re-randomises the obfuscated contact email on every request, so two downloads a minute apart never match byte for byte. Both hashes depend on the exact normalisation and extraction in `scripts/check-sleeper-docs.sh`; always produce them with that script. The machine-readable copy of these values is `scripts/sleeper-docs.baseline`.

### A.1 Introduction (general terms)

| Topic | What the docs say |
|---|---|
| Nature | Read-only HTTP API. Gives access to a user's leagues, drafts and rosters. |
| Cost / licence | Free for **non-commercial** use. Commercial use: contact Sleeper directly to discuss licensing (the email address is obfuscated on the page). |
| Auth | "No API Token is necessary", because you cannot modify content through the API. The User section repeats that no authentication is performed. |
| Rate limit | "stay under 1000 API calls per minute, otherwise, you risk being IP-blocked." No other limits are given. |
| Base URL | `https://api.sleeper.app/v1` (taken from the examples; there is no separate versioning section). |
| HTTP method | Every endpoint is `GET`. |
| Response headers / caching | Not documented. |
| Pagination | Not documented; no endpoint is paginated. |

### A.2 Endpoint index (17 documented routes)

| # | Docs section | Path (verbatim from the "HTTP Request" line) | Query params |
|---|---|---|---|
| 1 | User | `/v1/user/<username>` | — |
| 1b | User | `/v1/user/<user_id>` | — |
| 2 | Get all leagues for user | `/v1/user/<user_id>/leagues/<sport>/<season>` | — |
| 3 | Get a specific league | `/v1/league/<league_id>` | — |
| 4 | Getting rosters in a league | `/v1/league/<league_id>/rosters` | — |
| 5 | Getting users in a league | `/v1/league/<league_id>/users` | — |
| 6 | Getting matchups in a league | `/v1/league/<league_id>/matchups/<week>` | — |
| 7 | Getting the playoff bracket | `/v1/league/<league_id>/winners_bracket` | — |
| 7b | Getting the playoff bracket | `/v1/league/<league_id>/loses_bracket` ⚠ the curl example uses `losers_bracket` | — |
| 8 | Get transactions | `/v1/league/<league_id>/transactions/<round>` | — |
| 9 | Get traded picks | `/v1/league/<league_id>/traded_picks` | — |
| 10 | Get NFL state | `/v1/state/<sport>` | — |
| 11 | Get all drafts for user | `/v1/user/<user_id>/drafts/<sport>/<season>` | — |
| 12 | Get all drafts for a league | `/v1/league/<league_id>/drafts` | — |
| 13 | Get a specific draft | `/v1/draft/<draft_id>` | — |
| 14 | Get all picks in a draft | `/v1/draft/<draft_id>/picks` | — |
| 15 | Get traded picks in a draft | `/v1/draft/<draft_id>/traded_picks` | — |
| 16 | Fetch all players | `/v1/players/<sport>?position=<position>&active=<true>` | `position`, `active` (both optional) |
| 17 | Trending Players | `/v1/players/<sport>/trending/<type>?lookback_hours=<hours>&limit=<int>` | `lookback_hours`, `limit` (both optional) |

### A.3 Path and query parameter definitions (verbatim descriptions)

| Endpoint(s) | Parameter | Description as documented |
|---|---|---|
| 1 | `username` / `user_id` | Get the user by either one. Note: "the username of a user can change over time, so if you are storing information, you'll want to hold onto the user_id." |
| 2, 11 | `user_id` | "The numerical ID of the user." |
| 2, 11 | `sport` | "We only support "nfl" right now." |
| 2, 11 | `season` | "Season can be 2017, 2018, etc..." |
| 3 | `league_id` | "The ID of the league to retrieve" |
| 4 | `league_id` | "The ID of the league to retrieve rosters from" |
| 5 | `league_id` | "The ID of the league to retrieve rosters from" (copied from #4 in the docs) |
| 6 | `league_id` | "The ID of the league to retrieve matchups from" |
| 6 | `week` | "The week these matchups take place" |
| 7 | `league_id` | "The ID of the league to retrieve matchups from" |
| 8 | `league_id` | "The ID of the draft to retrieve picks for" ⚠ a copy-paste error in the docs; it is a league ID |
| 8 | `round` | "The week you want to pull from" |
| 9 | `league_id` | "The ID of the league to retrieve traded picks for" |
| 10 | `sport` | "nfl, nba, lcs, etc..." |
| 12 | `league_id` | "The ID of the league for which you are trying to retrieve drafts." |
| 13 | `draft_id` | "The ID of the draft to retrieve" |
| 14, 15 | `draft_id` | "The ID of the draft to retrieve picks for" |
| 16 | `position` (query) | "Only return players whose fantasy positions include this position, such as QB - optional" |
| 16 | `active` (query) | "Set to true to only return active players; any other value is ignored - optional" |
| 16 | — | "Both parameters can be used individually or combined." Filtered responses are much smaller than the full map. |
| 17 | `sport` | "The sport, such as nfl" |
| 17 | `type` | "Either add or drop" |
| 17 | `lookback_hours` (query) | "Number of hours to look back (default is 24) - optional" |
| 17 | `limit` (query) | "Number of results you want, (default is 25) - optional" |

### A.4 User and Avatars

**User object** (endpoint 1). The response is a single object.

| Field | Type | Example | Doc comment |
|---|---|---|---|
| `username` | string | `"sleeperuser"` | — |
| `user_id` | string | `"12345678"` | — |
| `display_name` | string | `"SleeperUser"` | — |
| `avatar` | string | `"cc12ec49965eb7856f84d71cf85306af"` | — |

**Avatars:** "Users and leagues have avatar images", with a thumbnail and a full-size image for each.

| Variant | URL template |
|---|---|
| Full size | `https://sleepercdn.com/avatars/<avatar_id>` |
| Thumbnail | `https://sleepercdn.com/avatars/thumbs/<avatar_id>` |

### A.5 Leagues

**League object** (endpoint 2 returns an array of these; endpoint 3 returns a single one). Description for #2: "This endpoint retrieves all leagues." For #3: "This endpoint retrieves a specific league."

| Field | Type | Example | Doc comment |
|---|---|---|---|
| `total_rosters` | int | `12` | — |
| `status` | string | `"pre_draft"` | `can also be "drafting", "in_season", or "complete"` |
| `sport` | string | `"nfl"` | — |
| `settings` | object | `{ settings object }` (placeholder; contents not documented) | — |
| `season_type` | string | `"regular"` | — |
| `season` | string | `"2018"` | — |
| `scoring_settings` | object | `{ scoring_settings object }` (placeholder; not documented) | — |
| `roster_positions` | array | `[ roster positions array ]` (placeholder; not documented) | — |
| `previous_league_id` | string | `"198946952535085056"` | — |
| `name` | string | `"Sleeperbot Friends League"` | — |
| `league_id` | string | `"289646328504385536"` | — |
| `draft_id` | string | `"289646328508579840"` | — |
| `avatar` | string | `"efaefa889ae24046a53265a3c71b8b64"` | — |

**Roster object** (endpoint 4 returns an array). "This endpoint retrieves all rosters in a league."

| Field | Type | Example | Doc comment |
|---|---|---|---|
| `starters` | array of string | `["2307", "2257", "4034", "147", "642", "4039", "515", "4149", "DET"]` | — |
| `settings` | object | see below | — |
| `settings.wins` | int | `5` | — |
| `settings.waiver_position` | int | `7` | — |
| `settings.waiver_budget_used` | int | `0` | — |
| `settings.total_moves` | int | `0` | — |
| `settings.ties` | int | `0` | — |
| `settings.losses` | int | `9` | — |
| `settings.fpts_decimal` | int | `78` | — |
| `settings.fpts_against_decimal` | int | `32` | — |
| `settings.fpts_against` | int | `1670` | — |
| `settings.fpts` | int | `1617` | — |
| `roster_id` | int | `1` | — |
| `reserve` | array | `[]` | — |
| `players` | array of string | 15 IDs, including `"DET"` | — |
| `owner_id` | string | `"188815879448829952"` | — |
| `league_id` | string | `"206827432160788480"` | — |

**League user object** (endpoint 5 returns an array). "This endpoint retrieves all users in a league. This also includes each user's display_name, avatar, and their metadata which sometimes includes a nickname they gave their team."

| Field | Type | Example | Doc comment |
|---|---|---|---|
| `user_id` | string | `"<user_id>"` | — |
| `username` | string | `"<username>"` | — |
| `display_name` | string | `"<display_name>"` | — |
| `avatar` | string | `"1233456789"` | — |
| `metadata` | object | `{"team_name": "Dezpacito"}` | — |
| `metadata.team_name` | string | `"Dezpacito"` | — |
| `is_owner` | bool | `true` | `is commissioner (there can be multiple commissioners)` |

**Matchup object** (endpoint 6 returns an array). "This endpoint retrieves all matchups in a league for a given week. Each object in the list represents one team. The two teams with the same matchup_id match up against each other. The starters is in an ordered list of player_ids, and players is a list of all player_ids in this matchup. The bench can be deduced by removing the starters from the players field."

| Field | Type | Example | Doc comment |
|---|---|---|---|
| `starters` | array of string | `["421", "4035", "3242", "2133", "2449", "4531", "2257", "788", "PHI"]` | — |
| `roster_id` | int | `1` | — |
| `players` | array of string | 15 IDs, including `"CLE"` | — |
| `matchup_id` | int | `2` | — |
| `points` | number | `20.0` | `total points for team based on league settings` |
| `custom_points` | null | `null` | `if commissioner overrides points manually` |

(Only these six fields are documented. `players_points` and `starters_points`, which the Data Model includes, are **not** in the docs. Confirm them against a real league's response when recording fixtures.)

**Bracket row** (endpoint 7, both brackets; returns an array). "This endpoint retrieves the playoff bracket for a league for 4, 6, and 8 team playoffs. Each row represents a matchup between 2 teams." This is the only section with an explicit type table:

| Field | Documented type | Documented description |
|---|---|---|
| `r` | int | "The round for this matchup, 1st, 2nd, 3rd round, etc." |
| `m` | int | "The match id of the matchup, unique for all matchups within a bracket." |
| `t1` | int | "The roster_id of a team in this matchup OR {w: 1} which means the winner of match id 1" |
| `t2` | int | "The roster_id of the other team in this matchup OR {l: 1} which means the loser of match id 1" |
| `w` | int | "The roster_id of the winning team, if the match has been played." |
| `l` | int | "The roster_id of the losing team, if the match has been played." |
| `t1_from` | object | "Where t1 comes from, either winner or loser of the match id, necessary to show bracket progression." |
| `t2_from` | object | "Where t2 comes from, either winner or loser of the match id, necessary to show bracket progression." |
| `p` | *(not in the table)* | Appears in the example on rows m5 (`p: 5`), m6 (`p: 1`) and m7 (`p: 3`). Undocumented. |

Docs example (7 rows, a 6-team bracket; unquoted keys as shown):
`{r:1,m:1,t1:3,t2:6}`, `{r:1,m:2,t1:4,t2:5}`, `{r:2,m:3,t1:1,t2:null,t2_from:{w:1}}`, `{r:2,m:4,t1:2,t2:null,t2_from:{w:2}}`, `{r:2,m:5,t1_from:{l:1},t2_from:{l:2},p:5}`, `{r:3,m:6,t1_from:{w:3},t2_from:{w:4},p:1}`, `{r:3,m:7,t1_from:{l:3},t2_from:{l:4},p:3}`. In every row `w` and `l` are `null`. Rows 5–7 also have `t1:null`, `t2:null`.

**Transaction object** (endpoint 8 returns an array). "This endpoint retrieves all free agent transactions, waivers and trades." The docs show two examples, a trade and a free-agent move.

| Field | Type | Example (trade / free_agent) | Doc comment |
|---|---|---|---|
| `type` | string | `"trade"` / `"free_agent"` | (on free_agent) `could be waiver or trade as well` |
| `transaction_id` | string | `"434852362033561600"` / `"434890120798142464"` | — |
| `status_updated` | int (epoch ms) | `1558039402803` / `1558048393967` | — |
| `status` | string | `"complete"` / `"complete"` | — |
| `settings` | null \| object | `null` / `null` | trade: `trades do not use this field`; free_agent: `could be {'waiver_bid': 44} if it's FAAB waivers` |
| `roster_ids` | array of int | `[2, 1]` / `[1]` | `roster_ids involved in this transaction` |
| `metadata` | null \| object | `null` / `null` | free_agent: `can contain notes in waivers like why it didn't go through` |
| `leg` | int | `1` / `1` | trade: `in football, this is the week` |
| `drops` | null \| object | `null` / `{"1736": 1}` | `player id 1736 dropped from roster_id 1` |
| `draft_picks` | array | 2 picks / `[]` | trade: `picks that were traded` |
| `draft_picks[].season` | string | `"2019"` | `the season this draft pick belongs to` |
| `draft_picks[].round` | int | `5` | `which round this draft pick is` |
| `draft_picks[].roster_id` | int | `1` | `original owner's roster_id` |
| `draft_picks[].previous_owner_id` | int | `1` | `previous owner's roster id (in this trade)` |
| `draft_picks[].owner_id` | int | `2` | `the new owner of this pick after the trade` |
| `creator` | string | `"160000000000000000"` | `user id who initiated the transaction` |
| `created` | int (epoch ms) | `1558039391576` / `1558048393967` | — |
| `consenter_ids` | array of int | `[2, 1]` / `[1]` | `roster_ids of the people who agreed to this transaction` / `the roster_ids who agreed to this transaction` |
| `adds` | null \| object | `null` / `{"2315": 1, ...}` | `player id 2315 added to roster_id 1` |
| `waiver_budget` | array | `[{"sender": 2, "receiver": 3, "amount": 55}]` / `[]` | trade: `roster_id 2 sends 55 FAAB dollars to roster_id 3`; free_agent: `this used for trades only involving FAAB` |
| `waiver_budget[].sender` / `.receiver` / `.amount` | int | `2` / `3` / `55` | — |

**Traded pick object** (endpoint 9 returns an array). "This endpoint retrieves all traded picks in a league, including future picks."

| Field | Type | Example | Doc comment |
|---|---|---|---|
| `season` | string | `"2019"`, `"2020"` | `which season the pick is for` |
| `round` | int | `5`, `3` | `which round the pick is` |
| `roster_id` | int | `1`, `2` | `roster_id of ORIGINAL owner` |
| `previous_owner_id` | int | `1`, `2` | `roster_id of the previous owner` |
| `owner_id` | int | `2`, `1` | `roster_id of current owner` |

**Sport state object** (endpoint 10 returns a single object). "This endpoint returns information about the current state for any sport."

| Field | Type | Example | Doc comment |
|---|---|---|---|
| `week` | int | `2` | `week` |
| `season_type` | string | `"regular"` | `pre, post, regular` |
| `season_start_date` | string (date) | `"2020-09-10"` | `regular season start` |
| `season` | string | `"2020"` | `current season` |
| `previous_season` | string | `"2019"` | — |
| `leg` | int | `2` | `week of regular season` |
| `league_season` | string | `"2021"` | `active season for leagues` |
| `league_create_season` | string | `"2021"` | `flips in December` |
| `display_week` | int | `3` | `Which week to display in UI, can be different than week` |

(`season_has_scores` is returned live but is **not** documented.)

### A.6 Drafts

**Draft object.** Endpoints 11 and 12 return arrays; endpoint 13 returns a single object. Descriptions: #11 "retrieves all drafts by a user"; #12 "retrieves all drafts for a league. Keep in mind that a league can have multiple drafts, especially dynasty leagues. Drafts are sorted by most recent to earliest. Most leagues should only have one draft."; #13 "retrieves a specific draft."

| Field | Type | Example | Doc comment | In #11/#12 example | In #13 example |
|---|---|---|---|---|---|
| `type` | string | `"snake"` | — | ✓ | ✓ |
| `status` | string | `"complete"` | — | ✓ | ✓ |
| `start_time` | int (epoch ms) | `1515700800000` | — | ✓ | ✓ |
| `sport` | string | `"nfl"` | — | ✓ | ✓ |
| `settings.teams` | int | `6` | — | ✓ | ✓ |
| `settings.slots_wr` | int | `2` | — | ✓ | ✓ |
| `settings.slots_te` | int | `1` | — | ✓ | ✓ |
| `settings.slots_rb` | int | `2` | — | ✓ | ✓ |
| `settings.slots_qb` | int | `1` | — | ✓ | ✓ |
| `settings.slots_k` | int | `1` | — | ✓ | ✓ |
| `settings.slots_flex` | int | `2` | — | ✓ | ✓ |
| `settings.slots_def` | int | `1` | — | ✓ | ✓ |
| `settings.slots_bn` | int | `5` | — | ✓ | ✓ |
| `settings.rounds` | int | `15` | — | ✓ | ✓ |
| `settings.pick_timer` | int | `120` | — | ✓ | ✓ |
| `season_type` | string | `"regular"` | — | ✓ | ✓ |
| `season` | string | `"2017"` | — | ✓ | ✓ |
| `metadata.scoring_type` | string | `"ppr"` | — | ✓ | ✓ |
| `metadata.name` | string | `"My Dynasty"` | — | ✓ | ✓ |
| `metadata.description` | string | `""` | — | ✓ | ✓ |
| `league_id` | string | `"257270637750382592"` | — | ✓ | ✓ |
| `last_picked` | int (epoch ms) | `1515700871182` | — | ✓ | ✓ |
| `last_message_time` | int (epoch ms) | `1515700942674` | — | ✓ | ✓ |
| `last_message_id` | string | `"257272036450111488"` | — | ✓ | ✓ |
| `draft_order` | null \| object | `null` (#11/#12); `{"12345678": 1, "23434332": 2, ...}` (#13) | `this is the user_id to draft slot mapping` | ✓ (null) | ✓ |
| `slot_to_roster_id` | object | `{"1": 10, "2": 3, "3": 5}` | `this is the draft slot to roster_id mapping` · `leagues have rosters, which have roster_ids` · `this means draft slot 1 (column 1) will go to roster 10, slot 2 will go to roster_id 3, etc` | ✗ | ✓ |
| `draft_id` | string | `"257270643320426496"` | — | ✓ | ✓ |
| `creators` | null | `null` | — | ✓ | ✓ |
| `created` | int (epoch ms) | `1515700610526` | — | ✓ | ✓ |

**Draft pick object** (endpoint 14 returns an array). "This endpoint retrieves all picks in a draft."

| Field | Type | Example (pick 1) | Doc comment |
|---|---|---|---|
| `player_id` | string | `"2391"` | — |
| `picked_by` | string | `"234343434"` | `user_id this pick will go to (not all leagues have users in every slot, this can be "")` |
| `roster_id` | **string** | `"1"` | `roster_id this pick will go to` |
| `round` | int | `5` | — |
| `draft_slot` | int | `5` | `which column this is on the draftboard` |
| `pick_no` | int | `1` | — |
| `metadata.team` | string | `"ARI"` | — |
| `metadata.status` | string | `"Injured Reserve"` | — |
| `metadata.sport` | string | `"nfl"` | — |
| `metadata.position` | string | `"RB"` | — |
| `metadata.player_id` | string | `"2391"` | — |
| `metadata.number` | **string** | `"31"` | — |
| `metadata.news_updated` | **string** (epoch ms) | `"1513007102037"` | — |
| `metadata.last_name` | string | `"Johnson"` | — |
| `metadata.injury_status` | string | `"Out"` (others in the examples: `""`, `"Probable"`) | — |
| `metadata.first_name` | string | `"David"` | — |
| `is_keeper` | null | `null` | — |
| `draft_id` | string | `"257270643320426496"` | — |

The third example pick (`player_id "536"`, `pick_no 3`) **omits** `roster_id`, `round` and `draft_slot`.

**Draft traded pick object** (endpoint 15 returns an array). "This endpoint retrieves all traded picks in a draft." The fields are the same as A.5's traded pick: `season` (string, `"2019"`), `round` (int; `which round the pick is`), `roster_id` (int; `roster_id of ORIGINAL owner`), `previous_owner_id` (int; `roster_id of the previous owner`), `owner_id` (int; `roster_id of current owner`). The example has no comment on `season`.

### A.7 Players

**Fetch all players** (endpoint 16). The response is an object keyed by `player_id`. Usage guidance, as documented:
- "Please use this call sparingly, as it is intended only to be used once per day at most to keep your player IDs updated."
- "The average size of this query is 5MB."
- Rosters and draft picks contain player IDs "which look like "1042", "2403", "CAR", etc", and `/players` provides the map to look them up.
- "You should save this information on your own servers"; "You do not need to call this endpoint more than once per day."
- Filtered responses (`position`, `active`) "are much smaller than the full 5MB player map".

Player value (example key `"3086"`):

| Field | Type | Example |
|---|---|---|
| `hashtag` | string | `"#TomBrady-NFL-NE-12"` |
| `depth_chart_position` | int | `1` |
| `status` | string | `"Active"` |
| `sport` | string | `"nfl"` |
| `fantasy_positions` | array of string | `["QB"]` |
| `number` | int | `12` |
| `search_last_name` | string | `"brady"` |
| `injury_start_date` | null | `null` |
| `weight` | string | `"220"` |
| `position` | string | `"QB"` |
| `practice_participation` | null | `null` |
| `sportradar_id` | string | `""` |
| `team` | string | `"NE"` |
| `last_name` | string | `"Brady"` |
| `college` | string | `"Michigan"` |
| `fantasy_data_id` | int | `17836` |
| `injury_status` | null | `null` |
| `player_id` | string | `"3086"` |
| `height` | string | `"6'4\""` |
| `search_full_name` | string | `"tombrady"` |
| `age` | int | `40` |
| `stats_id` | string | `""` |
| `birth_country` | string | `"United States"` |
| `espn_id` | string | `""` |
| `search_rank` | int | `24` |
| `first_name` | string | `"Tom"` |
| `depth_chart_order` | int | `1` |
| `years_exp` | int | `14` |
| `rotowire_id` | null | `null` |
| `rotoworld_id` | int | `8356` |
| `search_first_name` | string | `"tom"` |
| `yahoo_id` | null | `null` |

(32 fields. The docs have no per-field comments here. Note `number` is an int in this example but a string inside the draft pick `metadata`.)

**Trending players** (endpoint 17). "You can use this endpoint to get a list of trending players based on adds or drops in the past 24 hours." The response is an array.

| Field | Type | Example | Doc comment |
|---|---|---|---|
| `player_id` | string | `"1111"` | `the player_id` |
| `count` | int | `45` | `number or adds` (sic) |

Also documented:
- Embed iframe: `https://sleeper.app/embed/players/nfl/trending/add?lookback_hours=24&limit=25`, with `width="350" height="500"`.
- "Please give attribution to Sleeper you are using our trending data."

### A.8 Errors (as documented)

| Code | Documented meaning |
|---|---|
| 400 | "Bad Request -- Your request is invalid." |
| 404 | "Not Found -- The specified kitten could not be found." |
| 429 | "Too Many Requests -- You're requesting too many kittens! Slow down!" |
| 500 | "Internal Server Error -- We had a problem with our server. Try again later." |
| 503 | "Service Unavailable -- We're temporarily offline for maintenance. Please try again later." |

No error body format is documented. Neither is the `null`-body not-found behaviour; see D7 for what the live API does.

### A.9 Known errata and gaps in the docs (as of this snapshot)

1. The `loses_bracket` HTTP Request line conflicts with the `losers_bracket` curl example.
2. The transactions `league_id` description says "ID of the draft".
3. The league users `league_id` description says "retrieve rosters from".
4. The transactions path parameter is named `round` but described as a week.
5. The example JSON is not valid JSON: it has `//` comments, trailing commas, missing commas (after `"points": 20.0` and `"adds": null`), placeholders (`{ settings object }`), unquoted keys in the bracket example, and `...` elisions.
6. Undefined or undocumented: league `settings`, `scoring_settings` and `roster_positions` contents; bracket `p`; roster `reserve` element type; the meaning of the `*_decimal` fields; any transaction `status` other than `complete`; any draft `type` other than `snake`; any draft `status` other than `complete`; any `scoring_type` other than `ppr`.
7. Draft pick `roster_id` is a string in the example, while every other `roster_id` in the docs is an int.
8. The 404 and 429 descriptions are placeholder text ("kitten").

### A.10 Re-verification procedure

Run this when you suspect API drift, before each library release, and from the nightly live-smoke job. The procedure is implemented in **`scripts/check-sleeper-docs.sh`**. Don't re-implement it by hand, or the hashes won't be comparable.

```bash
scripts/check-sleeper-docs.sh                           # check against scripts/sleeper-docs.baseline
scripts/check-sleeper-docs.sh --diff path/to/old/page.txt   # also show a line diff against an earlier run
scripts/check-sleeper-docs.sh --update-baseline         # accept the current docs as the new baseline
```

What it does:
1. Downloads `https://docs.sleeper.com/` into `build/sleeper-docs/` (git-ignored): `headers.txt`, `page.html`.
2. Writes `page.normalized.html`, with the per-request Cloudflare email-obfuscation tokens (`/cdn-cgi/l/email-protection#<hex>`, `data-cfemail="<hex>"`) blanked.
3. Writes `page.txt`: strips `<script>`/`<style>`, turns block tags into newlines and table cells into ` | `, removes the remaining tags, unescapes entities and collapses blank lines.
4. Hashes `page.normalized.html` and `page.txt` and compares them with `scripts/sleeper-docs.baseline`.

Exit codes: `0` no change · `10` cosmetic change (HTML only) · `20` content change · `1` error. CI can treat `20` as "open an issue".

How to read the result:
- **Exit 0, both hashes match:** no change. Stop.
- **Exit 10, only the HTML hash differs:** cosmetic or asset change. Skim `page.txt` to confirm, then run `--update-baseline` and update A.0.
- **Exit 20, the text hash differs:** content changed. Diff `page.txt` against this appendix section by section, starting with the A.2 endpoint index, then each field table. For each difference:
  - **New field** → add it to the model (minor release).
  - **Removed or retyped field, or changed path** → breaking change for the library (major release).
  - **New endpoint** → new facade method (minor release).
  - **Errata fixed** → update A.9.

  Then run `--update-baseline`. It prints a ready-made A.11 row. Update A.0 and add that row to A.11 with a description of the change.
- Keep a copy of `page.txt` from each verification outside the published repo (for example a private gist, or the CI artifact store) so later diffs have a real baseline. It isn't committed to the repo, because it's Sleeper's content.

### A.11 Change log of the docs

| Date checked | Last-Modified | Text SHA-256 (first 12) | Change |
|---|---|---|---|
| 2026-11-26 | 2026-10-21 20:47:40 GMT | `4bf713cd298e` | Baseline captured for this spec. |
