#!/usr/bin/env bash
# The CI lock (issue #20 item 4): validates a consumer repo's committed
# .template-manifest.json against its working tree. A template-owned file may carry
# <!-- local --> ... <!-- /local --> regions (docs/architecture/local-slots.md) that
# are the consumer's own content by design; everything strictly between the markers is
# stripped before hashing, so a consumer's own slot edits never register as drift
# (docs/architecture/manifest.md is the full format).
#
# In this repo the check is a no-op by design: this repo is the template, not a pinned
# consumer, and carries no .template-manifest.json — a consumer wires this into its own
# CI as a required check.
#
# Usage:
#   .t-workflow/scripts/check-manifest.sh [--manifest <path>]
#     Verify every file the manifest lists against the working tree (paths are
#     resolved relative to the manifest's own directory).
#     Exit 0 = every file matches; 1 = at least one drifted or is missing (listed);
#     2 = no manifest found, or it lists no files — nothing was checked.
#   .t-workflow/scripts/check-manifest.sh --hash-file <path>
#     Print the normalized sha256 for one file. This is what t-update writes into the
#     manifest, and the only place the normalization rule is implemented — verify mode
#     below calls the same function, so the two can never compute it two different ways.
set -uo pipefail

normalized_hash() {
  local f="$1"
  # A symlink (CLAUDE.md, .agents/skills, ...) is compared by its target, never by
  # reading through it: some point at a directory, which `awk` cannot hash as text, and
  # "is this still the same link" is the precise question for an alias anyway.
  if [ -L "$f" ]; then
    printf 'symlink:%s' "$(readlink "$f")"
    return 0
  fi
  [ -f "$f" ] || return 1
  # Match only a line that *is* a marker (docs/architecture/local-slots.md: "one marker
  # per line"), never one that merely mentions "<!-- local -->" in prose — a doc
  # discussing the convention (this repo's own manifest.md, local-slots.md) would
  # otherwise trip skip mode on its own description and strip everything after it.
  awk '
    /^<!-- local -->[[:space:]]*$/    { print; skip=1; next }
    /^<!-- \/local -->[[:space:]]*$/  { skip=0; print; next }
    skip                { next }
    { print }
  ' "$f" | sha256sum | cut -d' ' -f1
}

if [ "${1:-}" = "--hash-file" ]; then
  f="${2:-}"
  if [ -z "$f" ]; then
    echo "usage: check-manifest.sh --hash-file <path>" >&2
    exit 2
  fi
  h=$(normalized_hash "$f") || { echo "check-manifest: no such file: $f" >&2; exit 2; }
  echo "$h"
  exit 0
fi

manifest=".template-manifest.json"
if [ "${1:-}" = "--manifest" ]; then
  manifest="${2:-}"
  if [ -z "$manifest" ]; then
    echo "usage: check-manifest.sh --manifest <path>" >&2
    exit 2
  fi
fi

if [ ! -f "$manifest" ]; then
  echo "check-manifest: no manifest at $manifest — nothing was checked" >&2
  exit 2
fi

dir="$(dirname "$manifest")"
bad=0
count=0
while IFS=$'\t' read -r path want; do
  count=$((count + 1))
  got=$(normalized_hash "$dir/$path" 2>/dev/null) || {
    echo "MISSING: $path (listed in manifest, not found in tree)"
    bad=1
    continue
  }
  if [ "$got" != "$want" ]; then
    echo "DRIFT: $path (manifest expects $want, tree has $got)"
    bad=1
  fi
done < <(jq -r '.files | to_entries[] | "\(.key)\t\(.value)"' "$manifest")

if [ "$count" -eq 0 ]; then
  echo "check-manifest: manifest at $manifest lists no files" >&2
  exit 2
fi

if [ "$bad" -eq 0 ]; then
  echo "OK: $count template-owned file(s) match the pinned manifest"
  exit 0
fi
exit 1
