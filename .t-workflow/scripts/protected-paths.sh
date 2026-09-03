#!/usr/bin/env bash
# The protected-surface list (CONSTITUTION.md §3), in machine-readable form.
#
# Why this exists: workflow §1.4 says rules the build enforces beat rules the model must
# remember. Protection decides whether a task needs a plan and an independent review, and
# it is keyed on the paths a diff touches — so three skills (/t-ship, /t-review,
# /t-status) and CI all need the same answer, and having each of them re-read prose
# bullets is exactly how two readings drift apart.
#
# CONSTITUTION.md §3 remains the authority a human reads; this file is its executable
# form. They change together, in the same task — the constitution says so.
#
# Usage:
#   .t-workflow/scripts/protected-paths.sh <path>...     exit 0 if ANY path is protected, 1 if none
#   .t-workflow/scripts/protected-paths.sh --stdin       read paths from stdin, one per line
#   .t-workflow/scripts/protected-paths.sh --list        print the patterns, one per line
#
# Exit codes — callers MUST distinguish 1 from 2:
#   0  at least one path is protected (they are echoed to stdout)
#   1  paths were checked and none are protected
#   2  no paths were given at all — nothing was checked
# Exit 2 exists because "asked about nothing" and "asked, found nothing" are the same
# value in a naive script, and every caller reads that value as "skip the plan and the
# review". An empty pipe is a broken caller, not a clean diff.
#   git diff --name-only <trunk>...HEAD | .t-workflow/scripts/protected-paths.sh --stdin
# Protected paths are echoed to stdout, so callers can report which ones matched.
#
# Prefer --stdin over `xargs`: on empty input GNU and BSD xargs behave differently (BSD
# does not invoke the command at all, so the pipeline reports success = "protected"),
# and a very large diff could make xargs batch, leaving only the last batch's exit
# status. --stdin has neither hazard.
set -uo pipefail

# Ordered, most-specific first. Matched with bash's == pattern operator (globs, not
# regex); a trailing /* means "anything under this directory".
patterns=(
  'CONSTITUTION.md'
  'AGENTS.md'
  'CLAUDE.md'
  'GEMINI.md'
  '.agents/*'
  'docs/adr/*'
  'docs/workflow.md'
  'docs/architecture/*'
  'docs/adapters/*'
  # The whole .claude tree, not just skills/: settings.json carries hooks (arbitrary
  # shell on tool calls) and permission allowlists, and agents/ defines subagents.
  # Config that changes what an agent does is as load-bearing as the skills.
  '.claude/*'
  '.mcp.json'
  '.cursor/*'
  '.github/*'
  '.t-workflow/scripts/*'
  # The installer stamps out every new repository from this template: a defect here is
  # inherited by every project it generates, and none of them are reviewed by anyone here.
  'installer/*'
  'docs/tasks/TEMPLATE.md'
  'docs/tasks/README.md'
  '.gitignore'
  'README.md'
  'LICENSE'
)

if [ "${1:-}" = "--list" ]; then
  printf '%s\n' "${patterns[@]}"
  exit 0
fi

paths=()
if [ "${1:-}" = "--stdin" ]; then
  while IFS= read -r line; do
    [ -n "$line" ] && paths+=("$line")
  done
else
  paths=("$@")
fi

if [ "${#paths[@]}" -eq 0 ]; then
  echo "protected-paths: no paths given — nothing was checked" >&2
  exit 2
fi

found=1
for path in "${paths[@]+"${paths[@]}"}"; do
  # Normalize a leading ./ so callers can pass either form.
  p="${path#./}"
  # Un-quote a git-style quoted path. With core.quotePath=true (git's default) a
  # non-ASCII name arrives as "docs/adr/002-caf\303\251.md" — surrounding quotes and
  # octal escapes — which would match no pattern and silently read as unprotected.
  # Callers should pass -c core.quotePath=false, but a gate this important does not
  # depend on every caller remembering.
  if [ "${p#\"}" != "$p" ] && [ "${p%\"}" != "$p" ]; then
    p=${p#\"}; p=${p%\"}
    p=$(printf '%b' "$p")
  fi
  for pat in "${patterns[@]}"; do
    # shellcheck disable=SC2053 -- pattern matching is the point
    if [[ "$p" == $pat ]]; then
      echo "$p"
      found=0
      break
    fi
  done
done
exit "$found"
