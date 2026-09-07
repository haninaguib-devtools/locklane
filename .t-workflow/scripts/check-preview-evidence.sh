#!/usr/bin/env bash
# Validates a preview-evidence JSON object (or array of them) against the shape
# docs/adapters/PREVIEW.md defines (issue #146): a project's own preview tooling
# reports one such object per attempt, and this script checks it is well-formed — never
# whether the preview itself succeeded, and never whether a human accepted it.
#
# This script is wired into no gate. Unlike check-verification-gate.sh (which /t-ship
# calls to block a merge), this one is called by nobody in this repository's own
# skills or CI — supplying preview evidence is never required (docs/adapters/PREVIEW.md
# Non-goals), so nothing here blocks a task that supplies none. It exists so a
# project's own tooling, or this repo's own plumbing-test.sh, can check a
# preview-evidence object against the contract before trusting it.
#
# Shape checked, per docs/adapters/PREVIEW.md:
#   {"commit":         "<full commit sha the evidence is for>",
#    "type":           "<free text — web, package, binary, image, environment, manual, …>",
#    "status":         "pending"|"ready"|"failed"|"expired",
#    "review_url":     "<optional>",
#    "instructions":   "<optional>",
#    "expiry":         "<optional>",
#    "failure_reason": "<present when, and only meaningfully read when, status is failed>"}
#
# `type`, `review_url`, `instructions`, and `expiry` are deliberately never validated
# beyond "if present, is a string" — PREVIEW.md keeps `type` free text on purpose so a
# project can name a preview kind this contract never anticipated, and a non-web
# preview legitimately carries no `review_url` at all. Only `commit`, `status`, and the
# failure_reason/status pairing are structurally required.
#
# Usage: .t-workflow/scripts/check-preview-evidence.sh <preview-evidence-json-file>
#   The file holds either one evidence object, or a JSON array of them (a project
#   reporting more than one preview attempt for the same or different commits).
# Exit 0 = every object in the file matches the shape above; 1 = at least one does not;
#          2 = bad usage or malformed input — an unreadable file must never read as
#          "nothing to check" (the same fail-safe direction every check-*.sh in this
#          repo takes).
set -uo pipefail

usage() {
  echo "usage: check-preview-evidence.sh <preview-evidence-json-file>" >&2
  exit 2
}

file="${1:-}"
[ -n "$file" ] && [ -f "$file" ] || usage

# Accept either a single object or an array; normalize to an array so the rest of this
# script has one shape to walk.
kind=$(jq -r 'if type == "array" then "array" elif type == "object" then "object" else "other" end' "$file" 2>/dev/null)
case "$kind" in
  array)  entries="$file" ;;
  object) entries=$(mktemp "${TMPDIR:-/tmp}/preview-evidence.XXXXXX"); jq '[.]' "$file" > "$entries"
          trap 'rm -f "$entries"' EXIT ;;
  *) echo "FAIL: $file is not a JSON object or array of preview-evidence objects" >&2; exit 2 ;;
esac

count=$(jq 'length' "$entries" 2>/dev/null) || { echo "FAIL: $file is not valid JSON" >&2; exit 2; }
if [ "$count" -eq 0 ]; then
  echo "OK: no preview-evidence entries (nothing to check)"
  exit 0
fi

fail=0

# Structural checks a jq boolean expression can settle in one pass per entry:
#   - commit and status are present and are strings
#   - status is one of the four recognized states
#   - failure_reason is present iff status == "failed"
#   - every optional string field, if present, is actually a string
bad=$(jq -r '
  to_entries[] | . as $e |
  ($e.value) as $v |
  ($v.commit // null) as $commit |
  ($v.status // null) as $status |
  ($v.failure_reason // null) as $fr |
  [
    (if ($commit | type) != "string" or $commit == "" then "entry \($e.key): missing or empty `commit`" else empty end),
    (if ($status | IN("pending","ready","failed","expired") | not) then "entry \($e.key): `status` is \($status // "missing"), not one of pending/ready/failed/expired" else empty end),
    (if $status == "failed" and ($fr | type) != "string" then "entry \($e.key): status is failed but `failure_reason` is missing" else empty end),
    (if $status != "failed" and $fr != null then "entry \($e.key): `failure_reason` present but status is \($status), not failed" else empty end),
    (["review_url","instructions","expiry","type"][] as $f | if ($v[$f] != null and ($v[$f] | type) != "string") then "entry \($e.key): `\($f)` is present but not a string" else empty end)
  ] | .[]
' "$entries")

if [ -n "$bad" ]; then
  fail=1
  echo "FAIL: preview-evidence shape violations:"
  while IFS= read -r line; do echo "  $line"; done <<< "$bad"
fi

[ "$fail" -eq 0 ] && echo "OK: every preview-evidence entry matches the contract shape (docs/adapters/PREVIEW.md)"
exit "$fail"
