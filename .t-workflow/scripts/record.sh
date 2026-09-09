#!/usr/bin/env bash
# The task record, docs/tasks/<id>-<slug>.md.
#   record.sh create <id>         write it from the issue and docs/tasks/TEMPLATE.md; prints the path
#   record.sh path <id>           print the existing record's path (exit 1 when none)
#   record.sh check <id> [file]   validate shape and honesty markers (exit 1 on failure, reasons printed)
set -uo pipefail
. "$(dirname "$0")/lib.sh"

cmd="${1:-}"; id="${2:-}"
[ -n "$cmd" ] && [ -n "$id" ] || { sed -n '2,5p' "$0" | sed 's/^# //'; exit 2; }
case "$id" in *[!0-9]*|'') die "<id> must be an issue number" ;; esac

find_record() { find "$TW_ROOT/docs/tasks" -maxdepth 1 -name "$id-*.md" 2>/dev/null | sort | head -1; }

case "$cmd" in
  path)
    f=$(find_record); [ -n "$f" ] || exit 1
    echo "${f#"$TW_ROOT"/}" ;;

  create)
    f=$(find_record)
    if [ -n "$f" ]; then echo "${f#"$TW_ROOT"/}"; exit 0; fi
    json=$(gh issue view "$id" --json title,body,parent) || die "could not read issue #$id"
    title=$(printf '%s' "$json" | jq -r .title)
    body=$(printf '%s' "$json" | jq -r .body | normalize)
    parent=$(printf '%s' "$json" | jq -r '.parent.number // empty')
    slug=$(slugify "$title")
    out="$TW_ROOT/docs/tasks/$id-$slug.md"
    mkdir -p "$TW_ROOT/docs/tasks"
    asked=$(printf '%s\n' "$body" | section "Goal" | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}')
    done_when=$(printf '%s\n' "$body" | section "Done when" | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}')
    not=$(printf '%s\n' "$body" | section "Non-goals" | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}')
    {
      echo "# $id — $title"
      if [ -n "$parent" ]; then echo "Issue: #$id · Part of: #$parent"; else echo "Issue: #$id"; fi
      echo; echo "## Asked"; echo "${asked:-<from the issue>}"
      echo; echo "## Done when"; echo "${done_when:-<from the issue>}"
      echo; echo "## Explicitly not"; echo "${not:-none}"
      echo; echo "## Decisions made along the way"; echo "- none"
      echo; echo "## Deviations / notes"; echo "- none"
    } > "$out"
    echo "${out#"$TW_ROOT"/}" ;;

  check)
    f="${3:-$(find_record)}"
    [ -n "$f" ] && [ -f "$f" ] || { echo "FAIL: no record docs/tasks/$id-<slug>.md"; exit 1; }
    case "$f" in */docs/tasks/"$id"-*.md|docs/tasks/"$id"-*.md) ;; *) echo "FAIL: $f is not docs/tasks/$id-<slug>.md"; exit 1 ;; esac
    rc=0
    head -1 "$f" | grep -qE "^# $id — ." || { echo "FAIL: first line must be '# $id — <title>'"; rc=1; }
    grep -qE "^Issue: #$id( ·|\$)" "$f" || { echo "FAIL: missing 'Issue: #$id' line"; rc=1; }
    for h in "Asked" "Done when" "Explicitly not" "Decisions made along the way" "Deviations / notes"; do
      grep -q "^## $h\$" "$f" || { echo "FAIL: missing '## $h' section"; rc=1; }
    done
    grep -qE '^<(the goal|observable|exclusions|from the issue)' "$f" && { echo "FAIL: template placeholder left unfilled"; rc=1; }
    [ "$rc" -eq 0 ] && echo "OK: record $f"
    exit "$rc" ;;
  *) die "unknown command '$cmd'" ;;
esac
