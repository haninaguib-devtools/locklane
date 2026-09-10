#!/usr/bin/env bash
# Local check: run Maven only when the diff touches a build input.
#
# This is the local caller of scripts/build-inputs.sh, mirroring
# .github/workflows/build.yml (ADR-109 D3): the script is the one definition of
# what Maven builds, and neither this file nor the workflow restates the set.
#
# Usage:
#   scripts/check.sh
# compares origin/main...HEAD via scripts/build-inputs.sh.
#
# Exit codes (fail closed, exactly as build.yml does):
#   build-inputs exit 0 (a build input changed) -> exec ./mvnw -B test
#   build-inputs exit 1 (no build input in this diff) -> print the skip line
#     below and exit 0 without running Maven
#   build-inputs exit 2 (empty/unreadable diff, nothing decided) -> run Maven
#
# When this script skips, record it in the PR's `## Checks run` as skipped,
# never as PASS, e.g.:
#   - `scripts/check.sh` — SKIPPED (no build inputs in diff) — commit <sha>
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

rc=0
"$ROOT/scripts/build-inputs.sh" origin/main || rc=$?
case "$rc" in
  0)
    echo "Build inputs changed (listed above): running ./mvnw -B test."
    exec "$ROOT/mvnw" -B test
    ;;
  1)
    echo "No build input in this diff: skipping ./mvnw -B test."
    exit 0
    ;;
  *)
    echo "build-inputs.sh could not decide (exit $rc): running ./mvnw -B test." >&2
    exec "$ROOT/mvnw" -B test
    ;;
esac
