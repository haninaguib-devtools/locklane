#!/usr/bin/env bash
# Which of the given paths are protected (.t-workflow/AGENTS.md §Protected paths, plus
# the `protected` globs in .t-workflow/config). Paths as arguments, or one per line on
# stdin when no arguments are given.
#   exit 0 = at least one protected path (each echoed); 1 = none; 2 = no paths given.
#   --list prints the patterns in force.
set -uo pipefail
. "$(dirname "$0")/lib.sh"

BUILTIN=".t-workflow/AGENTS.md .t-workflow/scripts .claude .agents .github/workflows AGENTS.md CLAUDE.md GEMINI.md"

# `protected` arrives via lib.sh's config source, unseen in a single-file analysis.
# shellcheck disable=SC2154
if [ "${1:-}" = "--list" ]; then printf '%s\n' $BUILTIN $protected; exit 0; fi

paths=$(if [ $# -gt 0 ]; then printf '%s\n' "$@"; else cat; fi | grep . || true)
[ -z "$paths" ] && exit 2

hits=0
while IFS= read -r p; do
  # shellcheck disable=SC2086
  if match_any "$p" $BUILTIN $protected; then echo "$p"; hits=$((hits + 1)); fi
done <<< "$paths"
[ "$hits" -gt 0 ] && exit 0
exit 1
