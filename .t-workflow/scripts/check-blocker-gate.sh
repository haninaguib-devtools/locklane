#!/usr/bin/env bash
# Mechanizes ADR-001 §D3.2 over the native blockedBy field (ADR-003) for CI: every
# blocker of the PR's issue must be closed *as completed*. A blocker closed as
# not-planned (cancelled via /t-cancel) was abandoned, not satisfied, and still fails
# this — closed alone is not enough.
#
# Usage: .t-workflow/scripts/check-blocker-gate.sh <blockers-json-file>
#   The file holds a JSON array of {"number":, "state":, "stateReason":} objects — the
#   shape `gh api graphql`'s `blockedBy(first: N) { nodes { number state stateReason } }`
#   returns. Testable directly against a fixture file; no live tracker call required.
# Exit 0 = every blocker is CLOSED/COMPLETED, or there are none; 1 = at least one is
# not; 2 = bad usage.
set -uo pipefail

file="${1:-}"
if [ -z "$file" ] || [ ! -f "$file" ]; then
  echo "usage: check-blocker-gate.sh <blockers-json-file>" >&2
  exit 2
fi

bad=$(jq -r '.[] | select(.state != "CLOSED" or .stateReason != "COMPLETED") |
  "#\(.number) \(.state)/\(.stateReason // "null")"' "$file")
if [ -z "$bad" ]; then
  echo "OK: every blocker is closed as completed"
  exit 0
fi
echo "FAIL: blocker(s) not closed as completed:"
printf '%s\n' "$bad" | sed 's/^/  /'
exit 1
