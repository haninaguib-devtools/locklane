#!/usr/bin/env bash
# Mechanizes /t-work Phase 3's PR title convention for CI: `[<id>] <issue title>`.
# Checks only the `[<id>]` prefix against the id parsed from the branch — the goal this
# implements ("the PR title starts [<id>] matching the branch") stops there; /t-ship's
# own squash-subject convention is free to differ once merged, and a human is free to
# edit the rest of a draft PR's title.
#
# Usage: .t-workflow/scripts/check-title-gate.sh <id> <pr-title>
# Exit 0 = the title starts with [<id>]; 1 = it does not; 2 = bad usage.
set -uo pipefail

id="${1:-}"
title="${2-}"
if [ -z "$id" ]; then
  echo "usage: check-title-gate.sh <id> <pr-title>" >&2
  exit 2
fi

case "$title" in
  "[$id]"*) echo "OK: title starts with [$id]"; exit 0 ;;
  *) echo "FAIL: title '$title' does not start with '[$id]'"; exit 1 ;;
esac
