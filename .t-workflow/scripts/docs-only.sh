#!/usr/bin/env bash
# Is every given path documentation? Documentation is any *.md file, anything under
# docs/, plus the `docs` globs in .t-workflow/config. Paths as arguments, or one per
# line on stdin when no arguments are given.
#   exit 0 = every path is documentation; 1 = not (each non-documentation path echoed);
#   2 = no paths given. --list prints the patterns in force.
set -uo pipefail
. "$(dirname "$0")/lib.sh"

BUILTIN="*.md docs"

if [ "${1:-}" = "--list" ]; then printf '%s\n' $BUILTIN $docs; exit 0; fi

paths=$(if [ $# -gt 0 ]; then printf '%s\n' "$@"; else cat; fi | grep . || true)
[ -z "$paths" ] && exit 2

other=0
while IFS= read -r p; do
  # shellcheck disable=SC2086
  match_any "$p" $BUILTIN $docs || { echo "$p"; other=$((other + 1)); }
done <<< "$paths"
[ "$other" -eq 0 ] && exit 0
exit 1
