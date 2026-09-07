#!/usr/bin/env bash
# Mechanizes the /t-ship precondition ADR-010 D4/D6 requires a concrete form for
# (issue #144): a required human-verification entry that is not resolved — or whose
# resolution is stale against the commit about to ship — blocks the merge gate the
# same way an unresolved blocker or a not-ready cold review does.
#
# This script never reads a task record or talks to git/the forge itself — like
# check-blocker-gate.sh and check-review-gate.sh, it judges a small JSON shape the
# calling skill (/t-ship) assembles first, so it stays pure and fixture-testable.
# `/t-ship`'s own precondition step documents how that shape is built from a task
# record's `## Verification` section (docs/architecture/verification.md has the full
# schema and the four states):
#
#   [{"role":     "<who/which role>",
#     "required":  true|false,
#     "state":    "pending"|"verified"|"rejected"|"risk-accepted",
#     "stale":     true|false}, …]
#
#   `stale` is computed by the caller, once per entry whose state is `verified` or
#   `risk-accepted`: false when the entry's recorded tested revision equals the PR's
#   current head commit; otherwise true unless the entry declared a `scope:` (globs)
#   and `git diff --name-only <revision> <head-sha>` touches none of them — the same
#   glob-match style protected-paths.sh uses. An entry with no `scope:` is stale by
#   default whenever the revision differs (docs/architecture/verification.md; ADR-010
#   D7's fail-toward-absent posture: an ambiguous "did this commit affect it?" defaults
#   to yes, never to a silent pass).
#
# A `risk-accepted` entry is a resolution, same as `verified` — it never blocks by
# itself — but this script's own messages always name the two states separately;
# nothing here (or in any caller) may print or treat `risk-accepted` as `verified`
# (CONSTITUTION.md §1.5 — a risk explicitly accepted is not represented as a passing
# check).
#
# Usage: .t-workflow/scripts/check-verification-gate.sh <verification-json-file>
# Exit 0 = every required entry is verified or risk-accepted, and none is stale, or
#          there are no entries at all (a plan/record with no `verification:` list —
#          the migration path for every task before this one); 1 = at least one
#          required entry is pending, rejected, or stale; 2 = bad usage or malformed
#          input — an unreadable file must never read as "nothing to check".
set -uo pipefail

usage() {
  echo "usage: check-verification-gate.sh <verification-json-file>" >&2
  exit 2
}

file="${1:-}"
[ -n "$file" ] && [ -f "$file" ] || usage

# An unreadable or malformed input must never read as "no entries" — fail in the safe
# direction, the same rule check-blocker-gate.sh applies to its own input file.
jq -e 'type == "array"' "$file" >/dev/null 2>&1 || {
  echo "FAIL: $file is not a JSON array of verification entries" >&2
  exit 2
}
jq -e 'all(.[]; (.role != null) and (.required | type) == "boolean" and
             (.state | IN("pending","verified","rejected","risk-accepted")) and
             (.stale | type) == "boolean")' "$file" >/dev/null 2>&1 || {
  echo "FAIL: $file has an entry missing role/required/state/stale, or an unrecognized state" >&2
  exit 2
}

count=$(jq 'length' "$file")
if [ "$count" -eq 0 ]; then
  echo "OK: no verification entries (a plan/record with no verification: list needs none)"
  exit 0
fi

fail=0

# Required, still pending or rejected: nothing has resolved it.
unresolved=$(jq -r '.[] | select(.required == true and (.state == "pending" or .state == "rejected")) |
  "role \(.role): required verification is \(.state), not yet resolved"' "$file")
if [ -n "$unresolved" ]; then
  fail=1
  echo "FAIL: unresolved required verification:"
  while IFS= read -r line; do echo "  $line"; done <<< "$unresolved"
fi

# Required, resolved (verified or risk-accepted) but stale against the commit about
# to ship: a new commit could have affected what was checked, so the resolution no
# longer speaks for this head commit.
stale=$(jq -r '.[] | select(.required == true and (.state == "verified" or .state == "risk-accepted") and .stale == true) |
  "role \(.role): \(.state) is stale — a later commit could affect what it checked; re-verify or re-accept at the new revision"' "$file")
if [ -n "$stale" ]; then
  fail=1
  echo "FAIL: stale required verification:"
  while IFS= read -r line; do echo "  $line"; done <<< "$stale"
fi

if [ "$fail" -eq 0 ]; then
  echo "OK: every required verification entry is verified or risk-accepted, and current"
fi

# Informational only — never blocking: entries not required, and required entries
# already resolved and current.
notrequired=$(jq -r '[.[] | select(.required == false)] | length' "$file")
[ "$notrequired" -gt 0 ] && echo "  ($notrequired entry/entries not required — never blocking)"

exit "$fail"
