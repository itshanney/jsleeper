# Test fixtures

Live responses from the Sleeper API (https://api.sleeper.app/v1), recorded by
`scripts/record-fixtures.sh`. Read-only GETs only. Key order is the server's.

- Recorded: 2026-11-26
- League season: 2026 (league status at recording time: in_season)
- Week used for matchups and transactions: 1
- The league, user and draft are derived from one public league ID; the IDs
  appear inside the fixture JSON.
- `players_nfl_sample.json` and `players_nfl_qb_active_sample.json` are small
  samples of the ~5 MB players map (starters of the first two rosters, one team
  defense, one player with a null team).
- Anonymised by the script before writing: usernames, display names, team
  names and avatars of users; the league name and avatar; draft names and
  descriptions; and every string in transaction `metadata` notes. Users are
  numbered consistently (`user1`, `User 1`, `Team 1`). Numeric IDs are kept so
  the data stays internally consistent.
- Edge cases the live league does not contain (bracket `*_from`, FAAB waiver,
  blank `picked_by`, null `matchup_id`) are hand-trimmed from real responses
  and checked in separately.

## Re-recording

    scripts/record-fixtures.sh [--league-id ID] [--week N]

The league ID comes from `--league-id`, `SLEEPER_LEAGUE_ID`, or the
`sleeper.league_id` key of `.env`. Re-running overwrites these files, so
review the diff before committing.
