#!/usr/bin/env bash
# The manifest's file-list source (docs/architecture/manifest.md): every tracked file
# under a protected pattern (CONSTITUTION.md §3, .t-workflow/scripts/protected-paths.sh), minus the
# paths that are genesis-only — stamped once for a consumer at bootstrap time
# (README.md) or deleted outright for every generated project (LICENSE, installer/,
# site/, the installer and pages workflows) by installer/bootstrap.sh. Those have
# nothing in a consumer repo to sync to, so they are never template-owned there.
#
# A pinned consumer repo may also carry a git-tracked file under a protected pattern
# that the template never shipped — a consumer-authored `.claude/skills/l-*/SKILL.md`
# or `docs/adr/1NN-*.md` is the common case. Pattern matching alone cannot tell that
# file apart from one the template actually owns, so when `.template-manifest.json` is
# present, --list narrows to the intersection of the pattern match and the manifest's
# own `files` map keys (docs/architecture/manifest.md § Which files are
# template-owned) — the manifest is the record of what this consumer's pinned tag
# actually shipped. With no manifest present (this repo itself; a fresh
# installer/bootstrap.sh run before the first commit; a t-update scratch clone of the
# template at a target tag, which never carries a manifest of its own), --list falls
# back to the pure pattern match, unchanged.
#
# Usage:
#   .t-workflow/scripts/template-owned-paths.sh --list
#     Print every manifest-eligible path, one per line, resolved against the current
#     working tree (real tracked files, not the raw protected-paths.sh patterns).
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
manifest=".template-manifest.json"

exclude=(
  'README.md'
  'LICENSE'
  'installer/*'
  'site/*'
  '.github/workflows/installer.yml'
  '.github/workflows/pages.yml'
)

is_excluded() {
  local p="$1" pat
  for pat in "${exclude[@]}"; do
    # shellcheck disable=SC2053 -- pattern matching is the point
    [[ "$p" == $pat ]] && return 0
  done
  return 1
}

list_by_pattern() {
  git ls-files | "$here/protected-paths.sh" --stdin 2>/dev/null | while IFS= read -r p; do
    is_excluded "$p" || printf '%s\n' "$p"
  done
}

if [ "${1:-}" != "--list" ]; then
  echo "usage: template-owned-paths.sh --list" >&2
  exit 2
fi

if [ -f "$manifest" ]; then
  # comm rejects input it judges unsorted using its own process locale, independent of
  # what sorted each stream — LC_ALL=C must cover comm itself, not only the two sorts,
  # or a locale where '.' collates after letters disagrees with C order and comm
  # refuses both streams outright.
  LC_ALL=C comm -12 \
    <(list_by_pattern | LC_ALL=C sort) \
    <(jq -r '.files | keys[]' "$manifest" 2>/dev/null | LC_ALL=C sort)
else
  list_by_pattern
fi
