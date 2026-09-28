#!/usr/bin/env bash
# Run one check at the committed HEAD and keep its raw output, so a reviewer can read
# the evidence instead of running the suite again.
#   check.sh [<command>]   the command, or the `check` in .t-workflow/config when none
# The output goes to <git common dir>/t-workflow/checks/, never the tree: a header
# (command, commit), the command's own output, then its exit code. Stdout is the one
# `## Checks run` line; stderr the output's last lines.
#   exit 0 = PASS; 1 = FAIL; 2 = not run (no command, or a tree HEAD does not describe)
set -uo pipefail
. "$(dirname "$0")/lib.sh"
cd "$TW_ROOT" || die "not in a repository"

# `check` arrives via lib.sh's config source, unseen in a single-file analysis.
# shellcheck disable=SC2154
cmd="${1:-$check}"
[ -n "$cmd" ] || die "no check command given and none configured in .t-workflow/config"
# Uncommitted or unadded files would be tested but not be in the commit the line names.
[ -z "$(git status --porcelain)" ] || die "the tree has uncommitted or untracked files; commit first, so the output belongs to HEAD"

sha=$(git rev-parse HEAD)
dir="$(cd "$(git rev-parse --git-common-dir)" && pwd)/t-workflow/checks"
mkdir -p "$dir" || die "cannot create $dir"
out="$dir/$(printf '%s' "$sha" | cut -c1-12)-$(slugify "$cmd").log"
( printf '# command: %s\n# commit: %s\n' "$cmd" "$sha"; bash -c "$cmd" 2>&1; rc=$?; printf '# exit: %s\n' "$rc"; exit "$rc" ) > "$out" < /dev/null
rc=$?
tail -n 20 "$out" >&2
result=PASS; [ "$rc" -eq 0 ] || result=FAIL
printf -- '- `%s` — %s — commit `%s` — output `%s`\n' "$cmd" "$result" "$sha" "$out"
[ "$rc" -eq 0 ]
