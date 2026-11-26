#!/usr/bin/env bash
#
# Record live Sleeper API responses as test fixtures (read-only GETs only).
#
# The league ID is taken from, in order: --league-id, $SLEEPER_LEAGUE_ID, or the
# `sleeper.league_id` key of .env at the repo root. The value is never printed.
# Everything else is derived from the league:
#   season, draft_id  <- /league/<id>
#   user_id           <- first owner_id in /league/<id>/rosters
#   week              <- --week (default 1)
#
# Usage:
#   scripts/record-fixtures.sh [--league-id ID] [--week N] [--out DIR]
#
# Personal data (usernames, display and team names, avatars, league and draft
# names, transaction notes) is anonymised before each fixture is written.
#
# Output (default src/test/resources/fixtures): one pretty-printed JSON file per
# route, in server key order, plus a README.md. The full 5 MB players map is
# kept under build/ only; the repo gets a ~20 player sample.
#
# Exit codes: 0 all calls returned 2xx; 1 any failure.
# Requires: bash 3.2+, curl, python3.

set -euo pipefail

BASE="https://api.sleeper.app/v1"
UA="jsleeper-fixture-recorder/0.1 (+https://github.com/itshanney/jsleeper)"
PAUSE_SECONDS="0.2"
QB_SAMPLE_SIZE=5

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
FIXTURE_DIR="$REPO_ROOT/src/test/resources/fixtures"
TMP_DIR="$REPO_ROOT/build/fixture-tmp"

LEAGUE_ID="${SLEEPER_LEAGUE_ID:-}"
WEEK=1

die() { echo "error: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --league-id) [ $# -ge 2 ] || die "--league-id needs a value"; LEAGUE_ID="$2"; shift 2 ;;
    --week)      [ $# -ge 2 ] || die "--week needs a value"; WEEK="$2"; shift 2 ;;
    --out)       [ $# -ge 2 ] || die "--out needs a value"; FIXTURE_DIR="$2"; shift 2 ;;
    -h|--help)   sed -n '2,23p' "$0"; exit 0 ;;
    *)           die "unknown argument: $1" ;;
  esac
done

case "$WEEK" in ''|*[!0-9]*) die "--week must be a positive integer" ;; esac
[ "$WEEK" -ge 1 ] || die "--week must be >= 1"

# Parse only the one key we need; never echo the file or the value.
if [ -z "$LEAGUE_ID" ] && [ -f "$REPO_ROOT/.env" ]; then
  LEAGUE_ID="$(grep -E '^sleeper\.league_id=' "$REPO_ROOT/.env" | head -n 1 | cut -d= -f2- \
    | tr -d '\r' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e "s/^['\"]//" -e "s/['\"]\$//" || true)"
fi
[ -n "$LEAGUE_ID" ] || die "no league ID: pass --league-id, set SLEEPER_LEAGUE_ID, or add sleeper.league_id to .env"
case "$LEAGUE_ID" in *[!0-9]*) die "league ID must be numeric" ;; esac

mkdir -p "$FIXTURE_DIR" "$TMP_DIR"
USER_MAP_FILE="$TMP_DIR/user_map.json"
rm -f "$USER_MAP_FILE"

# --- JSON helpers (python3 -I: isolated mode) ---------------------------------

# pretty NAME RAW DEST -> anonymises (see below), writes indented JSON in server key
# order; prints NULL, EMPTY or OK. Anonymisation happens here, before anything is
# written, so personal data never reaches src/. The raw responses stay under build/.
pretty() {
  python3 -I - "$1" "$2" "$3" "$USER_MAP_FILE" <<'PY'
import json, os, sys
name, raw, dest, map_file = sys.argv[1:5]

AVATAR_PLACEHOLDER = "0" * 32
# Contact or credential data on a user object; nulled defensively if Sleeper ever fills it in.
SENSITIVE_USER_KEY_PARTS = ("email", "phone", "token", "real_name", "password", "secret",
                            "cookie", "verification", "summoner_name")
BENIGN_FLAGS = ("on", "off")

# user_id -> N, stable within one run (league_users order first).
user_numbers = json.load(open(map_file)) if os.path.exists(map_file) else {}

def number_for(user_id):
    if user_id not in user_numbers:
        user_numbers[user_id] = len(user_numbers) + 1
    return user_numbers[user_id]

def avatar(value):
    return None if value is None else AVATAR_PLACEHOLDER

def anonymise_user(user):
    n = number_for(user["user_id"])
    for key in user:
        if any(part in key.lower() for part in SENSITIVE_USER_KEY_PARTS):
            user[key] = None
    if user.get("username") is not None:
        user["username"] = "user%d" % n
    if user.get("display_name") is not None:
        user["display_name"] = "User %d" % n
    if "avatar" in user:
        user["avatar"] = avatar(user["avatar"])
    meta = user.get("metadata")
    if isinstance(meta, dict):
        # Keep team_name (renamed) and plain on/off flags; drop other free text.
        kept = {}
        for key, value in meta.items():
            if key == "team_name":
                kept[key] = "Team %d" % n
            elif value in BENIGN_FLAGS:
                kept[key] = value
        user["metadata"] = kept

def anonymise_league(league):
    league["name"] = "Test League"
    if "avatar" in league:
        league["avatar"] = avatar(league["avatar"])
    if "last_author_avatar" in league:
        league["last_author_avatar"] = avatar(league["last_author_avatar"])
    if league.get("last_author_display_name") not in (None, "sys"):
        league["last_author_display_name"] = "User"
    if league.get("last_message_text_map") is not None:
        league["last_message_text_map"] = None

def anonymise_draft(draft):
    meta = draft.get("metadata")
    if isinstance(meta, dict):
        if "name" in meta:
            meta["name"] = "Test Draft"
        if "description" in meta:
            meta["description"] = ""

def redact_strings(value):
    if isinstance(value, str):
        return "redacted"
    if isinstance(value, dict):
        return {k: redact_strings(v) for k, v in value.items()}
    if isinstance(value, list):
        return [redact_strings(v) for v in value]
    return value

def anonymise(name, data):
    if data is None:
        return data
    if name == "league_users":
        for user in data:
            anonymise_user(user)
    elif name == "user":
        anonymise_user(data)
    elif name == "league":
        anonymise_league(data)
    elif name == "user_leagues":
        for league in data:
            anonymise_league(league)
    elif name == "draft":
        anonymise_draft(data)
    elif name in ("league_drafts", "user_drafts"):
        for draft in data:
            anonymise_draft(draft)
    elif name.startswith("league_transactions"):
        for tx in data:
            if tx.get("metadata") is not None:
                tx["metadata"] = redact_strings(tx["metadata"])
    elif name == "draft_picks":
        for pick in data:
            if pick.get("reactions"):
                pick["reactions"] = None
    return data

text = open(raw, encoding="utf-8").read()
data = anonymise(name, json.loads(text) if text.strip() else None)
json.dump(user_numbers, open(map_file, "w"))
with open(dest, "w", encoding="utf-8") as f:
    json.dump(data, f, indent=2, ensure_ascii=False)
    f.write("\n")
print("NULL" if data is None else "EMPTY" if data in ([], {}) else "OK")
PY
}

# json_field FILE KEY -> top-level string field of an object
json_field() {
  python3 -I - "$1" "$2" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8")) or {}
value = data.get(sys.argv[2])
print("" if value is None else value)
PY
}

# first_owner FILE -> first non-empty owner_id in a rosters array
first_owner() {
  python3 -I - "$1" <<'PY'
import json, sys
for roster in json.load(open(sys.argv[1], encoding="utf-8")) or []:
    if roster.get("owner_id"):
        print(roster["owner_id"])
        break
PY
}

# sample_players FULL ROSTERS DEST -> small map: starters of first two rosters,
# one team defense, one player with a null team.
sample_players() {
  python3 -I - "$1" "$2" "$3" <<'PY'
import json, re, sys
MAX_STARTERS = 16
full = json.load(open(sys.argv[1], encoding="utf-8"))
rosters = json.load(open(sys.argv[2], encoding="utf-8")) or []
ids = []
for roster in rosters[:2]:
    for pid in roster.get("starters") or []:
        if pid in full and pid not in ids:
            ids.append(pid)
ids = ids[:MAX_STARTERS]
if not any(re.fullmatch(r"[A-Z]{2,3}", pid) for pid in ids):
    ids.append(next(pid for pid in full if re.fullmatch(r"[A-Z]{2,3}", pid)))
if not any(full[pid].get("team") is None for pid in ids):
    ids.append(next(pid for pid, p in full.items()
                    if p.get("team") is None and re.fullmatch(r"\d+", pid)))
with open(sys.argv[3], "w", encoding="utf-8") as f:
    json.dump({pid: full[pid] for pid in ids}, f, indent=2, ensure_ascii=False)
    f.write("\n")
print(len(ids))
PY
}

# take_first RAW DEST N -> first N entries of an object, indented
take_first() {
  python3 -I - "$1" "$2" "$3" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8")) or {}
keep = dict(list(data.items())[:int(sys.argv[3])])
with open(sys.argv[2], "w", encoding="utf-8") as f:
    json.dump(keep, f, indent=2, ensure_ascii=False)
    f.write("\n")
print(len(keep))
PY
}

# --- HTTP ---------------------------------------------------------------------

WARNINGS=""
RECORDED=0

# download NAME PATH -> raw body in $TMP_DIR/NAME.raw; exits on any non-2xx.
# Only NAME is ever printed: paths contain the league ID.
download() {
  local name="$1" path="$2" code
  code="$(curl -sS -o "$TMP_DIR/$name.raw" -w '%{http_code}' \
    -H 'Accept: application/json' -A "$UA" "$BASE$path")" || die "$name: request failed"
  sleep "$PAUSE_SECONDS"
  case "$code" in 2*) ;; *) die "$name: HTTP $code" ;; esac
}

# fetch NAME PATH -> records $FIXTURE_DIR/NAME.json
fetch() {
  local name="$1" kind
  download "$name" "$2"
  kind="$(pretty "$name" "$TMP_DIR/$name.raw" "$FIXTURE_DIR/$name.json")" || die "$name: body is not valid JSON"
  RECORDED=$((RECORDED + 1))
  echo "recorded $name ($kind)"
  if [ "$kind" != "OK" ]; then
    WARNINGS="$WARNINGS  $name: $kind"$'\n'
  fi
}

# --- record -------------------------------------------------------------------

fetch league "/league/$LEAGUE_ID"
SEASON="$(json_field "$FIXTURE_DIR/league.json" season)"
DRAFT_ID="$(json_field "$FIXTURE_DIR/league.json" draft_id)"
STATUS="$(json_field "$FIXTURE_DIR/league.json" status)"
[ -n "$SEASON" ] || die "league has no season (league not found?)"
[ -n "$DRAFT_ID" ] || die "league has no draft_id; pick a league with a draft"

fetch league_rosters "/league/$LEAGUE_ID/rosters"
# League users first: their order defines the stable user numbers.
fetch league_users "/league/$LEAGUE_ID/users"
USER_ID="$(first_owner "$FIXTURE_DIR/league_rosters.json")"
[ -n "$USER_ID" ] || die "no roster has an owner_id"

fetch state_nfl "/state/nfl"
fetch user "/user/$USER_ID"
fetch user_leagues "/user/$USER_ID/leagues/nfl/$SEASON"
fetch "league_matchups_w$WEEK" "/league/$LEAGUE_ID/matchups/$WEEK"
fetch league_winners_bracket "/league/$LEAGUE_ID/winners_bracket"
fetch league_losers_bracket "/league/$LEAGUE_ID/losers_bracket"
fetch "league_transactions_w$WEEK" "/league/$LEAGUE_ID/transactions/$WEEK"
fetch league_traded_picks "/league/$LEAGUE_ID/traded_picks"
fetch user_drafts "/user/$USER_ID/drafts/nfl/$SEASON"
fetch league_drafts "/league/$LEAGUE_ID/drafts"
fetch draft "/draft/$DRAFT_ID"
fetch draft_picks "/draft/$DRAFT_ID/picks"
fetch draft_traded_picks "/draft/$DRAFT_ID/traded_picks"

# Players: the full map (~5 MB) stays under build/; only a sample is committed.
download players_nfl_full "/players/nfl"
SAMPLED="$(sample_players "$TMP_DIR/players_nfl_full.raw" "$FIXTURE_DIR/league_rosters.json" \
  "$FIXTURE_DIR/players_nfl_sample.json")" || die "players_nfl_sample: sampling failed"
RECORDED=$((RECORDED + 1))
echo "recorded players_nfl_sample ($SAMPLED players)"

download players_nfl_qb_active "/players/nfl?position=QB&active=true"
SAMPLED="$(take_first "$TMP_DIR/players_nfl_qb_active.raw" \
  "$FIXTURE_DIR/players_nfl_qb_active_sample.json" "$QB_SAMPLE_SIZE")" \
  || die "players_nfl_qb_active_sample: sampling failed"
RECORDED=$((RECORDED + 1))
echo "recorded players_nfl_qb_active_sample ($SAMPLED players)"

fetch trending_add "/players/nfl/trending/add?lookback_hours=24&limit=10"
fetch trending_drop "/players/nfl/trending/drop?lookback_hours=24&limit=10"

# --- README (never contains the league ID) --------------------------------------

cat > "$FIXTURE_DIR/README.md" <<README
# Test fixtures

Live responses from the Sleeper API (https://api.sleeper.app/v1), recorded by
\`scripts/record-fixtures.sh\`. Read-only GETs only. Key order is the server's.

- Recorded: $(date -u +%Y-%m-%d)
- League season: $SEASON (league status at recording time: $STATUS)
- Week used for matchups and transactions: $WEEK
- The league, user and draft are derived from one public league ID; the IDs
  appear inside the fixture JSON.
- \`players_nfl_sample.json\` and \`players_nfl_qb_active_sample.json\` are small
  samples of the ~5 MB players map (starters of the first two rosters, one team
  defense, one player with a null team).
- Anonymised by the script before writing: usernames, display names, team
  names and avatars of users; the league name and avatar; draft names and
  descriptions; and every string in transaction \`metadata\` notes. Users are
  numbered consistently (\`user1\`, \`User 1\`, \`Team 1\`). Numeric IDs are kept so
  the data stays internally consistent.
- Edge cases the live league does not contain (bracket \`*_from\`, FAAB waiver,
  blank \`picked_by\`, null \`matchup_id\`) are hand-trimmed from real responses
  and checked in separately.

## Re-recording

    scripts/record-fixtures.sh [--league-id ID] [--week N]

The league ID comes from \`--league-id\`, \`SLEEPER_LEAGUE_ID\`, or the
\`sleeper.league_id\` key of \`.env\`. Re-running overwrites these files, so
review the diff before committing.
README

echo
echo "done: $RECORDED fixtures in $FIXTURE_DIR (season $SEASON, status $STATUS, week $WEEK)"
if [ -n "$WARNINGS" ]; then
  echo "warning: null or empty responses (hand-make edge-case fixtures if needed):"
  printf '%s' "$WARNINGS"
fi
