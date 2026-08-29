#!/usr/bin/env bash
# The manifest's file-list source (docs/architecture/manifest.md): every tracked file
# under a protected pattern (CONSTITUTION.md §3, .t-workflow/scripts/protected-paths.sh), minus the
# paths that are genesis-only — stamped once for a consumer at bootstrap time
# (README.md) or deleted outright for every generated project (LICENSE, installer/,
# site/, the installer and pages workflows) by installer/bootstrap.sh. Those have
# nothing in a consumer repo to sync to, so they are never template-owned there.
#
# Usage:
#   .t-workflow/scripts/template-owned-paths.sh --list
#     Print every manifest-eligible path, one per line, resolved against the current
#     working tree (real tracked files, not the raw protected-paths.sh patterns).
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"

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

if [ "${1:-}" != "--list" ]; then
  echo "usage: template-owned-paths.sh --list" >&2
  exit 2
fi

git ls-files | "$here/protected-paths.sh" --stdin 2>/dev/null | while IFS= read -r p; do
  is_excluded "$p" || printf '%s\n' "$p"
done
