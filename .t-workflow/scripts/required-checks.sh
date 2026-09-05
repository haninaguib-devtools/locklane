#!/usr/bin/env bash
# The required-status-check contexts branch protection asserts on the trunk, as one
# list from one place (issue #126).
#
# The template's own contexts are fixed here: `checks` (.github/workflows/ci.yml) and
# `cold-review` (.github/workflows/review-gate.yml). A consumer repo adds its own by
# listing them in `.t-workflow/required-checks.local` at its repo root — one context
# name per line, blank lines and `#` comments ignored, the file simply absent when a
# consumer has nothing to add (docs/architecture/local-slots.md § The required-checks
# file). That file sits outside `.t-workflow/scripts/`, so it matches no protected
# pattern, is never template-owned, never hashed into the manifest, and is never
# touched by a sync — a consumer's list survives every `t-update` by construction.
#
# Two callers, one implementation — so they can never disagree about the list:
#   .t-workflow/scripts/github-bootstrap.sh asserts it in branch protection, and
#   /t-ship (Procedure steps 3 and 5) flips the live setting to it when a PR changes it.
#
# This script never calls the forge. The per-context "only once a real run exists on
# the trunk" guard takes the observed check-run names as input, so plumbing-test.sh
# can exercise it with pure fixtures.
#
# Usage:
#   required-checks.sh --list [--local-file <path>]
#       Print the union: the fixed contexts, then each consumer context in file order,
#       duplicates dropped. One name per line.
#   required-checks.sh --asserted <observed-names-file|-> [--local-file <path>]
#       Print what branch protection should assert given the check-run names actually
#       observed on the trunk (one per line; `-` reads them from stdin): the fixed
#       contexts unconditionally — the caller has already established that `checks`
#       ran, and asserting both template contexts on that evidence is the behaviour
#       github-bootstrap.sh always had — plus each consumer context that appears among
#       the observed names. A consumer context with no run yet is left out (and named
#       on stderr) rather than asserted, since GitHub can reject a context it has never
#       seen and a required check that never reports blocks every merge.
#   --local-file defaults to `.t-workflow/required-checks.local` relative to the
#   current directory; pass it explicitly from tests or from another checkout.
#
# Exit codes: 0 on success; 2 on a usage error or an unreadable observed-names file.
set -uo pipefail

fixed=(checks cold-review)

mode=""
observed_src=""
local_file=".t-workflow/required-checks.local"

while [ $# -gt 0 ]; do
  case "$1" in
    --list) mode=list; shift ;;
    --asserted)
      mode=asserted
      [ $# -ge 2 ] || { echo "usage: required-checks.sh --asserted <observed-names-file|->" >&2; exit 2; }
      observed_src="$2"; shift 2 ;;
    --local-file)
      [ $# -ge 2 ] || { echo "usage: --local-file <path>" >&2; exit 2; }
      local_file="$2"; shift 2 ;;
    *) echo "usage: required-checks.sh --list | --asserted <file|-> [--local-file <path>]" >&2; exit 2 ;;
  esac
done
[ -n "$mode" ] || { echo "usage: required-checks.sh --list | --asserted <file|-> [--local-file <path>]" >&2; exit 2; }

# The consumer's additions, in file order: strip a trailing `# comment`, surrounding
# whitespace, then skip blank lines. Missing file → no additions.
consumer=()
if [ -f "$local_file" ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%%#*}"
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [ -n "$line" ] && consumer+=("$line")
  done < "$local_file"
fi

# The union, deduplicated, order preserved: fixed first, then consumer.
union=()
seen_union=$'\n'
for name in "${fixed[@]}" ${consumer[@]+"${consumer[@]}"}; do
  case "$seen_union" in
    *$'\n'"$name"$'\n'*) continue ;;
  esac
  union+=("$name")
  seen_union+="$name"$'\n'
done

if [ "$mode" = list ]; then
  printf '%s\n' "${union[@]}"
  exit 0
fi

# --asserted: read the observed names.
observed=$'\n'
if [ "$observed_src" = "-" ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    [ -n "$line" ] && observed+="$line"$'\n'
  done
else
  [ -r "$observed_src" ] || { echo "required-checks: cannot read observed names from '$observed_src'" >&2; exit 2; }
  while IFS= read -r line || [ -n "$line" ]; do
    [ -n "$line" ] && observed+="$line"$'\n'
  done < "$observed_src"
fi

seen_fixed=$'\n'
for name in "${fixed[@]}"; do seen_fixed+="$name"$'\n'; done

for name in "${union[@]}"; do
  case "$seen_fixed" in
    *$'\n'"$name"$'\n'*) printf '%s\n' "$name"; continue ;;
  esac
  case "$observed" in
    *$'\n'"$name"$'\n'*) printf '%s\n' "$name" ;;
    *) echo "required-checks: '$name' (from $local_file) has no run on the trunk yet — left out until it has one" >&2 ;;
  esac
done
exit 0
