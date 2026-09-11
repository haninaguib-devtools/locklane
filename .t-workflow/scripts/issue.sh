#!/usr/bin/env bash
# Tracker reads the skills share.
#   issue.sh view <id>          JSON: number,title,state,stateReason,labels,body,parent,url
#   issue.sh plan <id>          print the issue's '## Plan' section (exit 1 none, 3 more than one)
#   issue.sh blockers <id>      JSON [{number,state,stateReason,title}]
#   issue.sh open-blockers <id> blockers not closed as completed, one per line (exit 1 when any)
#   issue.sh children <id>      JSON [{number,title,state,stateReason}]
#   issue.sh parent <id>        the parent issue's number, from the issue itself (exit 1 none)
#   issue.sh blocking <id>      JSON [{number,title,state}]: issues this one blocks
#   issue.sh ensure-label <name> [color] [description]
set -uo pipefail
. "$(dirname "$0")/lib.sh"
cmd="${1:-}"; id="${2:-}"
[ -n "$cmd" ] && [ -n "$id" ] || { sed -n '2,11p' "$0" | sed 's/^# //'; exit 2; }
case "$cmd" in
  view) gh issue view "$id" --json number,title,state,stateReason,labels,body,parent,url \
          --jq '. + {labels: [.labels[].name], parent: (.parent.number // null)}' ;;
  plan)
    body=$(gh issue view "$id" --json body -q .body | normalize)
    n=$(printf '%s\n' "$body" | count_sections Plan)
    [ "$n" -eq 0 ] && exit 1
    [ "$n" -gt 1 ] && { echo "more than one '## Plan' section" >&2; exit 3; }
    printf '%s\n' "$body" | section Plan ;;
  blockers) blockers_json "$id" ;;
  open-blockers) open_blockers "$id" ;;
  children) children_json "$id" | jq -c 'map({number,title,state,stateReason})' ;;
  parent) p=$(gh issue view "$id" --json parent --jq '.parent.number // empty'); [ -n "$p" ] && echo "$p" || exit 1 ;;
  blocking) gh issue view "$id" --json blocking --jq '[.blocking.nodes[]? | {number,title,state}]' ;;
  ensure-label)
    gh label list --limit 200 --json name -q '.[].name' | grep -qx "$id" \
      || gh label create "$id" --color "${3:-ededed}" --description "${4:-}" ;;
  *) die "unknown command '$cmd'" ;;
esac
