#!/usr/bin/env bash
# Does a diff touch anything Maven builds? (#602, ADR-109 D3)
#
# This is the one definition of the project's build inputs. AGENTS.md §Checks item 1
# (the local `./mvnw -B test` check) and `.github/workflows/ci.yml`'s build step both
# call this script and neither restates the set, so the two rules cannot drift — the
# same "one rule in one place, read from two sides" shape `.t-workflow/scripts/
# protected-paths.sh` gives CONSTITUTION.md §3.
#
# Build inputs: the root pom.xml, everything under engine/ and client/ (each module's
# own pom.xml included), the Maven wrapper (mvnw, mvnw.cmd, .mvn/), the CI workflow
# that runs the build, and this script. Everything else — CHANGELOG.md, docs/, the
# skills, the other scripts, the site — is invisible to Maven.
#
# One exclusion: a root pom.xml diff whose only changed lines are the `<revision>`
# line does not count. That line is the version string a release task bumps
# (docs/architecture/releasing.md); the Release workflow overrides it with -Drevision
# anyway, and the push to main after the merge still runs the full build.
#
# Usage:
#   scripts/build-inputs.sh <base-ref>     compare <base-ref>...HEAD (merge-base diff)
#
# Exit codes — callers MUST distinguish 1 from 2:
#   0  at least one changed path is a build input (they are echoed to stdout)
#   1  the diff was read and no changed path is a build input
#   2  the diff is empty or could not be read — nothing was decided, so a caller
#      that skips Maven on 1 must run it on 2 (fail closed)
set -uo pipefail

patterns=(
  'pom.xml'
  'engine/*'
  'client/*'
  'mvnw'
  'mvnw.cmd'
  '.mvn/*'
  '.github/workflows/ci.yml'
  'scripts/build-inputs.sh'
)

base="${1:-}"
if [ -z "$base" ] || [ "$base" = "--help" ]; then
  echo "usage: scripts/build-inputs.sh <base-ref>" >&2
  exit 2
fi

# core.quotePath=false so a non-ASCII path is not quoted and octal-escaped, which
# would make a pattern below fail to match it.
if ! changed=$(git -c core.quotePath=false diff --name-only "${base}...HEAD" 2>/dev/null); then
  echo "build-inputs: could not diff ${base}...HEAD — nothing was decided" >&2
  exit 2
fi
if [ -z "$changed" ]; then
  echo "build-inputs: ${base}...HEAD changes no files — nothing was decided" >&2
  exit 2
fi

# The root pom.xml is excluded only when every changed content line of its diff is the
# <revision> line. Anything else in that diff (a dependency, a plugin, a module, a mode
# change with no content lines at all) keeps it a build input.
revision_only_pom() {
  local lines
  lines=$(git diff "${base}...HEAD" -- pom.xml | grep -E '^[-+]' | grep -vE '^(\+\+\+|---) ')
  [ -n "$lines" ] || return 1
  ! printf '%s\n' "$lines" | grep -qvE '^[-+][[:space:]]*<revision>[^<]*</revision>[[:space:]]*$'
}

hits=()
while IFS= read -r path; do
  [ -n "$path" ] || continue
  for pat in "${patterns[@]}"; do
    # shellcheck disable=SC2254
    case "$path" in
      $pat)
        if [ "$path" = "pom.xml" ] && revision_only_pom; then
          break
        fi
        hits+=("$path")
        break
        ;;
    esac
  done
done <<< "$changed"

if [ "${#hits[@]}" -gt 0 ]; then
  printf '%s\n' "${hits[@]}"
  exit 0
fi
exit 1
