#!/usr/bin/env bash
#
# Download https://docs.sleeper.com/, extract its text deterministically, and
# compare both hashes with the recorded baseline (scripts/sleeper-docs.baseline).
#
# This is the canonical method behind Appendix A of
# specs/2026-11-26-sleeper-java-client.md. Do not change the extraction
# logic without re-baselining, or the text hash will no longer be comparable.
#
# Usage:
#   scripts/check-sleeper-docs.sh [--out DIR] [--diff PREVIOUS_PAGE_TXT] [--update-baseline]
#
#   --out DIR            Where to write headers.txt, page.html,
#                        page.normalized.html, page.txt
#                        (default: build/sleeper-docs, which is git-ignored)
#   --diff FILE          Also print a unified diff of page.txt against FILE
#                        (a page.txt saved from an earlier run)
#   --update-baseline    Overwrite scripts/sleeper-docs.baseline with this run's
#                        fingerprint. Only do this after reviewing the change and
#                        updating Appendix A of the spec.
#
# Exit codes:
#   0   no change: both hashes match the baseline
#   10  cosmetic change: normalised-HTML hash differs, text hash matches
#   20  content change: text hash differs; review against Appendix A
#   1   error (network, missing tool, bad arguments)
#
# Requires: bash 3.2+, curl, python3, shasum (macOS) or sha256sum (Linux).

set -euo pipefail

URL="https://docs.sleeper.com/"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BASELINE_FILE="$SCRIPT_DIR/sleeper-docs.baseline"

OUT_DIR="$REPO_ROOT/build/sleeper-docs"
DIFF_AGAINST=""
UPDATE_BASELINE=0

die() { echo "error: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --out)              [ $# -ge 2 ] || die "--out needs a directory"; OUT_DIR="$2"; shift 2 ;;
    --diff)             [ $# -ge 2 ] || die "--diff needs a file"; DIFF_AGAINST="$2"; shift 2 ;;
    --update-baseline)  UPDATE_BASELINE=1; shift ;;
    -h|--help)          awk 'NR>1 && /^#/ {sub(/^# ?/, ""); print; next} NR>1 {exit}' "$0"; exit 0 ;;
    *)                  die "unknown argument: $1 (try --help)" ;;
  esac
done

command -v curl    >/dev/null 2>&1 || die "curl not found"
command -v python3 >/dev/null 2>&1 || die "python3 not found"
if command -v shasum >/dev/null 2>&1; then
  sha256() { shasum -a 256 "$1" | awk '{print $1}'; }
elif command -v sha256sum >/dev/null 2>&1; then
  sha256() { sha256sum "$1" | awk '{print $1}'; }
else
  die "neither shasum nor sha256sum found"
fi

mkdir -p "$OUT_DIR"
HEADERS="$OUT_DIR/headers.txt"
HTML="$OUT_DIR/page.html"
TEXT="$OUT_DIR/page.txt"
NORM="$OUT_DIR/page.normalized.html"

# 1. Download (follow redirects, fail on HTTP errors).
curl -sSfL -D "$HEADERS" -o "$HTML" "$URL" || die "download of $URL failed"

# 2. Normalise the HTML and extract its text. This is the canonical extraction;
#    keep it byte-for-byte stable.
#    Normalisation: Cloudflare email obfuscation re-randomises the encoded
#    contact address on every request (href="/cdn-cgi/l/email-protection#<hex>"
#    and data-cfemail="<hex>"), so those tokens are blanked before hashing.
#    Runs isolated (-I) and reads the downloaded file by path only.
python3 -I - "$HTML" "$NORM" "$TEXT" <<'PY'
import html, re, sys
src, norm, dst = sys.argv[1], sys.argv[2], sys.argv[3]
raw = open(src, encoding="utf-8").read()
n = re.sub(r"(/cdn-cgi/l/email-protection#)[0-9a-fA-F]+", r"\1", raw)
n = re.sub(r'(data-cfemail=")[0-9a-fA-F]+(")', r"\1\2", n)
open(norm, "w", encoding="utf-8").write(n)
s = raw
s = re.sub(r"(?is)<(script|style)[^>]*>.*?</\1>", "", s)
s = re.sub(r"(?i)<br\s*/?>", "\n", s)
s = re.sub(r"(?i)</(p|div|h[1-6]|li|tr|pre|table)>", "\n", s)
s = re.sub(r"(?i)<(td|th)[^>]*>", " | ", s)
s = re.sub(r"<[^>]+>", "", s)
s = html.unescape(s)
s = re.sub(r"\n\s*\n+", "\n", s)
open(dst, "w").write(s)
PY

# 3. Fingerprint this run.
NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
LAST_MODIFIED="$(grep -i '^last-modified:' "$HEADERS" | tail -1 | cut -d' ' -f2- | tr -d '\r' || true)"
HTML_SHA="$(sha256 "$NORM")"
TEXT_SHA="$(sha256 "$TEXT")"
HTML_BYTES="$(wc -c < "$HTML" | tr -d ' ')"
TEXT_BYTES="$(wc -c < "$TEXT" | tr -d ' ')"
TEXT_LINES="$(grep -c . "$TEXT" || true)"

# 4. Load the baseline.
[ -f "$BASELINE_FILE" ] || die "baseline file not found: $BASELINE_FILE"
baseline() { grep "^$1=" "$BASELINE_FILE" | head -1 | cut -d= -f2-; }
BASE_CHECKED="$(baseline CHECKED_AT)"
BASE_LASTMOD="$(baseline LAST_MODIFIED)"
BASE_HTML="$(baseline HTML_SHA256)"
BASE_TEXT="$(baseline TEXT_SHA256)"

echo "Sleeper docs check: $URL"
echo
printf '  %-15s %-66s %s\n' ""              "this run ($NOW)"  "baseline ($BASE_CHECKED)"
printf '  %-15s %-66s %s\n' "Last-Modified" "${LAST_MODIFIED:-<none>}" "$BASE_LASTMOD"
printf '  %-15s %-66s %s\n' "HTML SHA-256*"  "$HTML_SHA"        "$BASE_HTML"
printf '  %-15s %-66s %s\n' "Text SHA-256"  "$TEXT_SHA"        "$BASE_TEXT"
printf '  %-15s %s bytes HTML, %s bytes text, %s non-blank lines\n' "Size" "$HTML_BYTES" "$TEXT_BYTES" "$TEXT_LINES"
echo
echo "  * hash of page.normalized.html (Cloudflare email tokens blanked)"
echo "  Files: $OUT_DIR/{headers.txt,page.html,page.normalized.html,page.txt}"
echo

# 5. Optional diff against an earlier extracted text.
if [ -n "$DIFF_AGAINST" ]; then
  [ -f "$DIFF_AGAINST" ] || die "--diff file not found: $DIFF_AGAINST"
  echo "---- diff $DIFF_AGAINST -> $TEXT ----"
  diff -u "$DIFF_AGAINST" "$TEXT" || true
  echo "---- end diff ----"
  echo
fi

# 6. Optionally re-baseline.
if [ "$UPDATE_BASELINE" -eq 1 ]; then
  cat > "$BASELINE_FILE" <<EOF
# Fingerprint of https://docs.sleeper.com/ that the current spec was written against.
# See Appendix A.0 / A.11 of specs/2026-11-26-sleeper-java-client.md.
# Update with: scripts/check-sleeper-docs.sh --update-baseline   (after reviewing the change)
CHECKED_AT=$NOW
LAST_MODIFIED=$LAST_MODIFIED
HTML_SHA256=$HTML_SHA
TEXT_SHA256=$TEXT_SHA
EOF
  echo "Baseline updated: $BASELINE_FILE"
  echo "Remember to add a row to Appendix A.11 in the spec:"
  echo "  | ${NOW%%T*} | $LAST_MODIFIED | \`${TEXT_SHA:0:12}\` | <describe the change> |"
  exit 0
fi

# 7. Verdict.
if [ "$TEXT_SHA" = "$BASE_TEXT" ] && [ "$HTML_SHA" = "$BASE_HTML" ]; then
  echo "RESULT: NO CHANGE. Docs match the baseline."
  exit 0
elif [ "$TEXT_SHA" = "$BASE_TEXT" ]; then
  echo "RESULT: COSMETIC CHANGE. HTML differs but the extracted text is identical."
  echo "        Skim page.txt to confirm, then run with --update-baseline."
  exit 10
else
  echo "RESULT: CONTENT CHANGE. Extracted text differs from the baseline."
  echo "        Diff page.txt against Appendix A (start with A.2, the endpoint index),"
  echo "        update the spec and the library as needed, then run with --update-baseline."
  [ -n "$DIFF_AGAINST" ] || echo "        Tip: pass --diff <earlier page.txt> to see a line diff."
  exit 20
fi
