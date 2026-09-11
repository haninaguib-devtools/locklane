#!/usr/bin/env bash
# The task record, docs/tasks/<id>-<slug>.md.
#   record.sh create <id>                                  write it from the issue and docs/tasks/TEMPLATE.md; prints the path
#   record.sh path <id>                                     print the existing record's path (exit 1 when none)
#   record.sh check <id> [file]                             validate shape and honesty markers (exit 1 on failure, reasons printed)
#   record.sh agent <id> <stage> <harness> <model> [note]   append one entry to the '## Agents' section, creating it
#                                                            when it is not there yet
#   record.sh trailers <id>                                 print one commit trailer per '## Agents' entry whose
#                                                            stage carries one (plan, work, work (fix)); nothing
#                                                            when the record has no section. /t-ship adds
#                                                            Reviewed-By and Shipped-By itself: a review's
#                                                            attribution is read live, never from a stale entry
#                                                            here, and ship never writes to the record at all.
set -uo pipefail
. "$(dirname "$0")/lib.sh"

cmd="${1:-}"; id="${2:-}"
[ -n "$cmd" ] && [ -n "$id" ] || { sed -n '2,9p' "$0" | sed 's/^# //'; exit 2; }
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
      echo; echo "## Agents"
    } > "$out"
    echo "${out#"$TW_ROOT"/}" ;;

  agent)
    stage="${3:-}"; harness="${4:-}"; model="${5:-}"; note="${6:-}"
    [ -n "$stage" ] && [ -n "$harness" ] && [ -n "$model" ] || die "usage: record.sh agent <id> <stage> <harness> <model> [note]"
    f=$(find_record) || die "no record docs/tasks/$id-<slug>.md"
    entry="- $stage: $harness / $model"
    [ -n "$note" ] && entry="$entry ($note)"
    if grep -q '^## Agents$' "$f"; then printf '%s\n' "$entry" >> "$f"
    else { echo; echo "## Agents"; echo "$entry"; } >> "$f"; fi
    echo "${f#"$TW_ROOT"/}" ;;

  trailers)
    f=$(find_record) || exit 0
    body=$(normalize < "$f")
    [ "$(printf '%s\n' "$body" | count_sections "Agents")" -eq 1 ] || exit 0
    printf '%s\n' "$body" | section "Agents" | grep -v '^[[:space:]]*$' | while IFS= read -r line; do
      case "$line" in "- "*) ;; *) continue ;; esac
      entry="${line#- }"
      stage="${entry%%:*}"
      rest="${entry#*: }"
      case "$stage" in
        plan) key="Planned-By" ;;
        work|"work (fix)") key="Implemented-By" ;;
        # a 'review' entry here is a past fix cycle's, possibly stale; /t-ship reads
        # the current gating review directly for Reviewed-By instead of trusting it.
        *) key="" ;;
      esac
      [ -n "$key" ] || continue
      echo "$key: ${rest%% (*}"
    done ;;

  check)
    f="${3:-$(find_record)}"
    [ -n "$f" ] && [ -f "$f" ] || { echo "FAIL: no record docs/tasks/$id-<slug>.md"; exit 1; }
    case "$f" in */docs/tasks/"$id"-*.md|docs/tasks/"$id"-*.md) ;; *) echo "FAIL: $f is not docs/tasks/$id-<slug>.md"; exit 1 ;; esac
    rc=0
    head -1 "$f" | grep -qE "^# $id — ." || { echo "FAIL: first line must be '# $id — <title>'"; rc=1; }
    grep -qE "^Issue: #$id( ·|\$)" "$f" || { echo "FAIL: missing 'Issue: #$id' line"; rc=1; }
    # Every section exactly once, in order, with content beyond a template placeholder.
    body=$(normalize < "$f")
    prev=0
    for h in "Asked" "Done when" "Explicitly not" "Decisions made along the way" "Deviations / notes"; do
      n=$(printf '%s\n' "$body" | count_sections "$h")
      if [ "$n" -eq 0 ]; then echo "FAIL: missing '## $h' section"; rc=1; continue; fi
      [ "$n" -gt 1 ] && { echo "FAIL: duplicated '## $h' section"; rc=1; }
      at=$(printf '%s\n' "$body" | grep -n "^## $h\$" | head -1 | cut -d: -f1)
      if [ "$at" -le "$prev" ]; then echo "FAIL: '## $h' section out of order"; rc=1; else prev="$at"; fi
      content=$(printf '%s\n' "$body" | section "$h" | grep -v '^[[:space:]]*$' || true)
      if [ -z "$content" ]; then echo "FAIL: '## $h' section is empty"; rc=1;
      elif ! printf '%s\n' "$content" | grep -qv '^<'; then echo "FAIL: '## $h' section is a template placeholder"; rc=1; fi
    done
    # '## Agents': absent entirely is a legacy record from before this section existed
    # and passes; present, it must carry real entries — the first stage that touches a
    # legacy record adds the section, and from then on this applies to it too.
    na=$(printf '%s\n' "$body" | count_sections "Agents")
    if [ "$na" -gt 1 ]; then echo "FAIL: duplicated '## Agents' section"; rc=1;
    elif [ "$na" -eq 1 ]; then
      acontent=$(printf '%s\n' "$body" | section "Agents" | grep -v '^[[:space:]]*$' || true)
      if [ -z "$acontent" ]; then echo "FAIL: '## Agents' section has no entries"; rc=1;
      elif ! printf '%s\n' "$acontent" | grep -qv '^<'; then echo "FAIL: '## Agents' section is a template placeholder"; rc=1; fi
    fi
    [ "$rc" -eq 0 ] && echo "OK: record $f"
    exit "$rc" ;;
  *) die "unknown command '$cmd'" ;;
esac
