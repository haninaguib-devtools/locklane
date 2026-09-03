#!/usr/bin/env bash
# Verifies a task record's identity lines and template shape against the task id
# derived from its branch — the mechanical form of the record-honesty rule (goal item
# 4 of #30). ci.yml's `record` job locates the file for an ordinary task PR; this
# checks what is inside it. In `--multi` mode (ADR-004: a driven initiative's aggregate
# PR to the trunk, branch `wip/<initiative-id>-integration`) it also *locates* one record
# per child named in the PR body's `Task: #<id>` lines, since there is no single id to
# derive a lone record's path from — the initiative issue carries no record of its own
# (docs/workflow.md §6.1).
# Section headings are read from the template itself, never hardcoded, so this cannot
# drift from docs/tasks/TEMPLATE.md.
#
# Usage: .t-workflow/scripts/check-record.sh <id> <record-file> [template-file]
#          Ordinary task: verify one known record file against one known id.
#        .t-workflow/scripts/check-record.sh --multi <pr-body-file> [template-file]
#          Driven-initiative aggregate PR (changed paths on stdin): find and verify one
#          record per `Task: #<id>` line in the PR body.
# Exit 0 = every record checked is real; 1 = a check failed (reasons on stdout);
# 2 = bad usage.
set -uo pipefail

# Shared by both modes: does $2 look like a real record for task $1 against template $3?
check_one() {
  local id="$1" file="$2" template="$3" fail=0
  if [ ! -f "$file" ]; then
    echo "FAIL: record file '$file' does not exist"
    return 1
  fi

  if ! grep -qE "^# ${id} — .+" "$file"; then
    echo "FAIL: heading does not match '# ${id} — <Title>' in $file"
    fail=1
  fi

  if ! grep -qE "Issue: #${id}([^0-9]|\$)" "$file"; then
    echo "FAIL: no 'Issue: #${id}' line in $file"
    fail=1
  fi

  while IFS= read -r heading; do
    if ! grep -qF "$heading" "$file"; then
      echo "FAIL: missing template section '$heading' in $file"
      fail=1
    fi
  done < <(grep -E '^## ' "$template")

  return "$fail"
}

if [ "${1:-}" = "--multi" ]; then
  prbody="${2:-}"
  template="${3:-docs/tasks/TEMPLATE.md}"
  if [ -z "$prbody" ] || [ ! -f "$prbody" ]; then
    echo "usage: check-record.sh --multi <pr-body-file> [template-file]  (changed paths on stdin)" >&2
    exit 2
  fi
  if [ ! -f "$template" ]; then
    echo "usage: template '$template' does not exist — cannot verify sections" >&2
    exit 2
  fi

  changed=$(cat)
  ids=$(grep -oE '^Task: #[0-9]+' "$prbody" | grep -oE '[0-9]+' | sort -un)
  if [ -z "$ids" ]; then
    echo "FAIL: no 'Task: #<id>' lines in the PR body — a driven initiative's aggregate"
    echo "  PR must name at least one included child (ADR-004 Decision 3)."
    exit 1
  fi

  fail=0
  for id in $ids; do
    bucket=$(printf '%06d' $(( (10#$id / 100) * 100 )))
    recfile=$(printf '%s\n' "$changed" | grep -E "^docs/tasks/${bucket}/${id}-[^/]+\.md$" | head -1)
    if [ -z "$recfile" ]; then
      echo "FAIL: Task: #${id} named in the PR body but no docs/tasks/${bucket}/${id}-<slug>.md in the diff"
      fail=1
      continue
    fi
    echo "Found the record for task $id at $recfile — checking it is real:"
    check_one "$id" "$recfile" "$template" || fail=1
  done

  [ "$fail" -eq 0 ] && echo "OK: every Task: #<id> in the PR body has a real record"
  exit "$fail"
fi

id="${1:-}"
file="${2:-}"
template="${3:-docs/tasks/TEMPLATE.md}"

if [ -z "$id" ] || [ -z "$file" ]; then
  echo "usage: check-record.sh <id> <record-file> [template-file]" >&2
  exit 2
fi
if [ ! -f "$template" ]; then
  echo "usage: template '$template' does not exist — cannot verify sections" >&2
  exit 2
fi

check_one "$id" "$file" "$template"
fail=$?
[ "$fail" -eq 0 ] && echo "OK: record matches task $id and carries every template section"
exit "$fail"
