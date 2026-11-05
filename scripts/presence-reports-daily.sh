#!/usr/bin/env bash
# presence-reports-daily.sh — the daily sweep for #417.
#
# Runs the aggregator, appends its aggregate to a rolling CSV, prints the reports that still
# have no human reply, and posts a digest to #217 ONLY when the numbers changed since the last
# run. "Nothing new" is never posted: a daily no-change message trains everyone to ignore the
# channel.
#
#   bash scripts/presence-reports-daily.sh                 # real sweep via gh
#   bash scripts/presence-reports-daily.sh --dry-run       # decide, print, don't post
#   bash scripts/presence-reports-daily.sh --fixture F     # offline rehearsal
#
# State lives outside the repo:
#   ${PRESENCE_REPORTS_STATE:-${XDG_STATE_HOME:-$HOME/.local/state}/hermes-vox/presence-reports}
# Override the target with PRESENCE_REPORTS_ISSUE (default 217) and the repo with
# PRESENCE_REPORTS_REPO or GH_REPO.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DRY_RUN=0
FIXTURE=()
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --fixture) [ $# -ge 2 ] || { echo "presence-reports-daily: --fixture needs a path" >&2; exit 2; }
               FIXTURE=(--fixture "$2"); shift 2 ;;
    -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
    *) echo "presence-reports-daily: unknown argument: $1" >&2; exit 2 ;;
  esac
done

STATE="${PRESENCE_REPORTS_STATE:-${XDG_STATE_HOME:-$HOME/.local/state}/hermes-vox/presence-reports}"
# A rehearsal is isolated: its snapshot, rolling history and hash all carry a suffix, so it
# can neither consume the real change nor duplicate a row in the real rolling CSV.
SUFFIX=""
[ "$DRY_RUN" -eq 1 ] && SUFFIX=".dry-run"
OUT="$STATE/latest$SUFFIX.csv"
MALFORMED="$STATE/latest$SUFFIX.malformed.csv"
SUMMARY="$STATE/latest$SUFFIX.txt"
ROLLING="$STATE/rolling$SUFFIX.csv"
HASHFILE="$STATE/last.sha256$SUFFIX"
ISSUE="${PRESENCE_REPORTS_ISSUE:-217}"
REPO="${PRESENCE_REPORTS_REPO:-${GH_REPO:-}}"
REPO_ARGS=()
[ -n "$REPO" ] && REPO_ARGS=(--repo "$REPO")

mkdir -p "$STATE"

# The aggregator prints the human summary (including the unacknowledged list) and writes the
# CSV. Tee so the summary survives for the digest even when the run is on a cron.
set +e
python3 "$ROOT/scripts/presence-reports.py" \
  --out "$OUT" --malformed-out "$MALFORMED" "${REPO_ARGS[@]}" "${FIXTURE[@]}" \
  | tee "$SUMMARY"
rc="${PIPESTATUS[0]}"
set -e
if [ "$rc" -ne 0 ]; then
  echo "presence-reports-daily: the aggregator failed (exit $rc); nothing posted." >&2
  exit "$rc"
fi

# No reports is not a digest. A first run against an empty label must stay silent too; the
# "never post nothing new" rule applies to the first run as much as the hundredth.
issue_count="$(sed -nE 's/^Presence reports — ([0-9]+) issue.*/\1/p' "$SUMMARY" | head -n 1)"
if [ "${issue_count:-0}" -eq 0 ]; then
  echo "presence-reports-daily: no reports — no digest posted."
  exit 0
fi

# Rolling history: one timestamped copy of every row, forever. The latest snapshot is $OUT.
ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
if [ ! -s "$ROLLING" ]; then
  printf 'snapshot_utc,%s\n' "$(head -n 1 "$OUT")" > "$ROLLING"
fi
tail -n +2 "$OUT" | while IFS= read -r line; do
  printf '%s,%s\n' "$ts" "$line"
done >> "$ROLLING"

# The digest's identity is the aggregate itself: group rows, the malformed/missing list and
# the missing-metric counts. Acknowledgements are deliberately not in here — a reply is not a
# number changing, and it must not trigger a post.
hash="$(
  { cat "$OUT"; cat "$MALFORMED"; grep -E '^  [a-z0-9_]+: [0-9]+/[0-9]+ parsed report' "$SUMMARY" || true; } \
    | sha256sum | awk '{print $1}'
)"
last=""
[ -f "$HASHFILE" ] && last="$(cat "$HASHFILE")"

if [ "$hash" = "$last" ]; then
  echo "presence-reports-daily: numbers unchanged since the last run — no digest posted."
  exit 0
fi

# The digest is the printed summary without the local CSV path.
BODY="$STATE/digest.md"
grep -v '^CSV: ' "$SUMMARY" > "$BODY"
echo "presence-reports-daily: numbers changed — digest worth posting."
if [ "$DRY_RUN" -eq 1 ]; then
  printf '%s\n' "$hash" > "$HASHFILE"
  echo "presence-reports-daily: --dry-run, so #$ISSUE was not posted."
  exit 0
fi

if [ -n "$REPO" ]; then
  gh issue comment "$ISSUE" --repo "$REPO" --body-file "$BODY"
else
  gh issue comment "$ISSUE" --body-file "$BODY"
fi
printf '%s\n' "$hash" > "$HASHFILE"
echo "presence-reports-daily: digest posted to #$ISSUE."
