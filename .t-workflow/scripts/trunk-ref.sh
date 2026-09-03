#!/usr/bin/env bash
# Resolves this checkout's actual trunk branch name, so skills and scripts never
# hardcode `main` — a consumer whose default branch is named something else (e.g.
# `master`) would otherwise fail silently against a nonexistent `origin/main` ref.
# The one place the `main` fallback is written; every other call site uses this
# script's output instead of a literal branch name.
#
# Usage: .t-workflow/scripts/trunk-ref.sh   (prints the trunk name, e.g. "main")
set -uo pipefail

ref=$(git symbolic-ref --short refs/remotes/origin/HEAD 2>/dev/null)
if [ -n "$ref" ]; then
  echo "${ref#origin/}"
else
  echo "main"
fi
