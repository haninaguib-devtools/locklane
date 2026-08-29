#!/usr/bin/env bash
# Mechanizes /t-ship precondition 2 for CI: on a protected surface, the PR's latest
# review must read `readiness: ready`, its `isolation:` line must not be "same
# session" (forbidden on a protected surface by /t-review's own Isolation rule), and it
# must be newer than the head commit — a later commit means the review is about a
# different diff.
#
# Changed paths come from stdin, the same contract as protected-paths.sh --stdin, so
# this stays a pure function over its inputs and is testable against fixtures.
#
# Usage: .t-workflow/scripts/check-review-gate.sh <head-commit-iso8601> <reviews-json-file>
#   The file holds a JSON array of {"submittedAt":, "body":} objects, any order — the
#   latest by submittedAt is the one judged.
# Exit 0 = not protected, or protected with a current, cold, ready review.
# Exit 1 = protected and the review is missing, stale, warm, or not ready.
# Exit 2 = nothing to check, or bad usage.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"

head_time="${1:-}"
file="${2:-}"
if [ -z "$head_time" ] || [ -z "$file" ] || [ ! -f "$file" ]; then
  echo "usage: check-review-gate.sh <head-commit-iso8601> <reviews-json-file>  (changed paths on stdin)" >&2
  exit 2
fi

protected=$("$here/protected-paths.sh" --stdin); rc=$?
case "$rc" in
  1) echo "OK: no protected path in this diff — no review required"; exit 0 ;;
  2) echo "FAIL: protected-paths.sh saw no paths — broken pipeline"; exit 2 ;;
esac
echo "Protected paths in this diff:"
printf '%s\n' "$protected" | sed 's/^/  /'

latest=$(jq -c 'sort_by(.submittedAt) | last // empty' "$file")
if [ -z "$latest" ] || [ "$latest" = "null" ]; then
  echo "FAIL: no review on this PR"
  exit 1
fi

submitted=$(printf '%s' "$latest" | jq -r '.submittedAt')
body=$(printf '%s' "$latest" | jq -r '.body')

fail=0
if ! printf '%s' "$body" | grep -qE '^readiness: ready$'; then
  echo "FAIL: latest review does not read 'readiness: ready'"
  fail=1
fi
if printf '%s' "$body" | grep -qE '^isolation: same session'; then
  echo "FAIL: latest review's isolation is 'same session' — not permitted on a protected surface"
  fail=1
fi
if ! printf '%s' "$body" | grep -qE '^isolation: '; then
  echo "FAIL: latest review carries no 'isolation:' line — treated as unknown, not cold"
  fail=1
fi
# ISO 8601 'Z' timestamps of matching precision sort correctly as plain strings.
if [[ "$submitted" < "$head_time" ]]; then
  echo "FAIL: latest review ($submitted) predates the head commit ($head_time) — commits landed after it"
  fail=1
fi

[ "$fail" -eq 0 ] && echo "OK: current cold ready review at $submitted"
exit "$fail"
