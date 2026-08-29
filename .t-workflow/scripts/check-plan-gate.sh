#!/usr/bin/env bash
# Mechanizes CONSTITUTION.md §3's plan-before-protected-work rule for CI: a PR whose
# diff touches a protected surface must have exactly one `## Plan` section on its issue
# (an issue carries exactly one, always — see docs/tasks/README.md and /t-plan).
#
# Changed paths come from stdin, the same contract as protected-paths.sh --stdin; the
# issue body comes from a file. That keeps this a pure function over both inputs, so it
# is testable against fixtures without a live tracker call.
#
# Usage: .t-workflow/scripts/check-plan-gate.sh <issue-body-file>   (changed paths on stdin)
# Exit 0 = not protected, or protected with exactly one Plan section.
# Exit 1 = protected and the Plan section is missing or duplicated.
# Exit 2 = nothing to check (stdin was empty) or bad usage.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"

body="${1:-}"
if [ -z "$body" ] || [ ! -f "$body" ]; then
  echo "usage: check-plan-gate.sh <issue-body-file>  (changed paths on stdin)" >&2
  exit 2
fi

protected=$("$here/protected-paths.sh" --stdin); rc=$?
case "$rc" in
  1) echo "OK: no protected path in this diff — no plan required"; exit 0 ;;
  2) echo "FAIL: protected-paths.sh saw no paths — broken pipeline"; exit 2 ;;
esac
echo "Protected paths in this diff:"
printf '%s\n' "$protected" | sed 's/^/  /'

count=$(grep -cE '^## Plan$' "$body")
case "$count" in
  0) echo "FAIL: the issue carries no '## Plan' section, but the diff touches a protected surface"
     exit 1 ;;
  1) echo "OK: exactly one '## Plan' section"
     exit 0 ;;
  *) echo "FAIL: the issue carries $count '## Plan' sections — an issue must carry exactly one"
     exit 1 ;;
esac
