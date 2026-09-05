#!/usr/bin/env bash
# Mechanizes ADR-001 §D3.2 over the native blockedBy field (ADR-003), for CI and for
# /t-work's and /t-drive's own gate: every blocker of the PR's issue must be closed *as
# completed*. A blocker closed as not-planned (cancelled via /t-cancel) was abandoned,
# not satisfied, and still fails this — closed alone is not enough.
#
# Two opt-in inputs carry the driven-initiative reading ADR-009 ratifies (#127); with
# neither, the plain rule above is exactly what runs:
#
#   --siblings <initiative-id> <siblings-json-file>
#     A blocker that is a sibling child of initiative <initiative-id> counts as
#     satisfied when its PR is merged into the initiative's integration branch — head
#     `wip/<sibling>-*`, base `wip/<initiative-id>-integration`, state MERGED — and its
#     latest cold review reads `readiness: ready`. The file holds a JSON array, one
#     entry per sibling blocker, in the shape the forge reads already return:
#       [{"number": <sibling-id>,
#         "prs":     [{"state":, "headRefName":, "baseRefName":}, …],   forge:pr-find-by-task
#         "reviews": [{"submittedAt":, "body":}, …]}]                   forge:pr-reviews
#     A blocker absent from the file, or present without such a PR, or whose latest
#     review (by submittedAt) is not `readiness: ready`, is judged exactly as before.
#
#   --pr-base <ref>
#     The base branch of the PR being gated. When it is a driven initiative's
#     integration branch (`wip/<n>-integration`) this exits 0 without judging: the
#     driving session's own gate, run with --siblings, already judged this child, and
#     CI has no sibling dispositions to re-judge it with (ADR-009 D2). Any other base
#     judges normally.
#
# Usage: .t-workflow/scripts/check-blocker-gate.sh <blockers-json-file>
#            [--siblings <initiative-id> <siblings-json-file>] [--pr-base <ref>]
#   The blockers file holds a JSON array of {"number":, "state":, "stateReason":}
#   objects — the shape `gh api graphql`'s `blockedBy(first: N) { nodes { number state
#   stateReason } }` returns. Testable directly against fixture files; no live tracker
#   or forge call required.
# Exit 0 = every blocker is CLOSED/COMPLETED or satisfied by its driven merge, or there
# are none, or the PR targets an integration branch; 1 = at least one is not; 2 = bad
# usage.
set -uo pipefail

usage() {
  echo "usage: check-blocker-gate.sh <blockers-json-file> [--siblings <initiative-id> <siblings-json-file>] [--pr-base <ref>]" >&2
  exit 2
}

file=""
initiative=""
siblings=""
pr_base=""
while [ $# -gt 0 ]; do
  case "$1" in
    --siblings)
      [ $# -ge 3 ] || usage
      initiative="$2"; siblings="$3"; shift 3 ;;
    --pr-base)
      [ $# -ge 2 ] || usage
      pr_base="$2"; shift 2 ;;
    --*) usage ;;
    *)
      [ -z "$file" ] || usage
      file="$1"; shift ;;
  esac
done
[ -n "$file" ] && [ -f "$file" ] || usage
if [ -n "$siblings" ]; then
  [ -f "$siblings" ] || usage
  printf '%s' "$initiative" | grep -qE '^[0-9]+$' || usage
fi

# An unreadable input must never read as "no blockers" — fail in the safe direction.
jq -e 'type == "array"' "$file" >/dev/null 2>&1 || { echo "FAIL: $file is not a JSON array of blockers" >&2; exit 2; }
if [ -n "$siblings" ]; then
  jq -e 'type == "array"' "$siblings" >/dev/null 2>&1 || { echo "FAIL: $siblings is not a JSON array of sibling dispositions" >&2; exit 2; }
fi

if printf '%s' "$pr_base" | grep -qE '^wip/[0-9]+-integration$'; then
  echo "OK: the PR targets $pr_base, a driven initiative's integration branch — the driving session's own gate (with sibling dispositions) judged this child; not re-judged here (ADR-009)"
  exit 0
fi

# Blockers not closed as completed, one "#<number> <state>/<stateReason>" per line.
bad=$(jq -r '.[] | select(.state != "CLOSED" or .stateReason != "COMPLETED") |
  "#\(.number) \(.state)/\(.stateReason // "null")"' "$file")
if [ -z "$bad" ]; then
  echo "OK: every blocker is closed as completed"
  exit 0
fi

# The driven reading: which of those are siblings merged into the integration branch
# with a ready review? Computed once from the siblings file.
satisfied=""
if [ -n "$siblings" ]; then
  satisfied=$(jq -r --arg init "$initiative" '
    .[] | select(
      (.number | tostring) as $n |
      ((.prs // []) | any(
        .state == "MERGED"
        and .baseRefName == ("wip/" + $init + "-integration")
        and ((.headRefName // "") | test("^wip/" + $n + "-"))))
      and (((.reviews // []) | sort_by(.submittedAt) | last // {}) | (.body // "")
           | split("\n") | any(. == "readiness: ready")))
    | .number' "$siblings")
fi

fail=0
while IFS= read -r line; do
  num=${line#\#}; num=${num%% *}
  state=${line#* }; state=${state%%/*}
  # Only a still-OPEN sibling can be satisfied by its driven merge: a cancelled one was
  # abandoned (ADR-001 §D3.2), whatever its PR history says.
  if [ "$state" = "OPEN" ] && [ -n "$satisfied" ] && printf '%s\n' "$satisfied" | grep -qx "$num"; then
    echo "OK: blocker #$num is merged into wip/$initiative-integration with a ready cold review — satisfied by its driven merge; its issue stays open until the aggregate PR reaches the trunk (ADR-009)"
  else
    [ "$fail" -eq 0 ] && echo "FAIL: blocker(s) not closed as completed:"
    echo "  $line"
    fail=1
  fi
done <<< "$bad"

[ "$fail" -eq 0 ] && echo "OK: every blocker is closed as completed or satisfied by its driven merge"
exit "$fail"
