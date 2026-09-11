#!/usr/bin/env bash
# Shared helpers, sourced by the other scripts. Never run directly. bash 3.2 compatible.
# Config defaults below are read by the sourcing scripts; invisible single-file.
# shellcheck disable=SC2034

# Patterns from config and the built-in sets are matched by glob_match, never expanded
# by the shell against the working directory.
set -f

TW_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
TW_DIR="$TW_ROOT/.t-workflow"
TW_SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

die() { echo "ERROR: $*" >&2; exit 2; }

# Config, with defaults. Consumer-owned file; keys are documented there. The file is
# parsed as plain key="value" lines and never executed; anything else on a line is
# ignored, and a line shaped like an assignment that the parser does not accept (a
# single-quoted value, a quote inside the value, a $VAR) is said once on stderr, so an
# empty value is never silent. TW_CONFIG_FILE points the parse at another file (ci.sh
# exports a merged base-policy copy so the gate's children judge by the same values).
check=""; protected=""; docs=""; exempt=""; reviewer_model=""
load_config() {
  check=""; protected=""; docs=""; exempt=""; reviewer_model=""
  local cfg="${1:-}" line kv n=0
  [ -n "$cfg" ] && [ -f "$cfg" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    n=$((n + 1)); line="${line%$'\r'}"
    kv=$(printf '%s' "$line" | sed -n -E 's/^[[:space:]]*(check|protected|docs|exempt|reviewer_model)="([^"$]*)"[[:space:]]*$/\1=\2/p')
    case "$kv" in
      check=*) check="${kv#check=}" ;;
      protected=*) protected="${kv#protected=}" ;;
      docs=*) docs="${kv#docs=}" ;;
      exempt=*) exempt="${kv#exempt=}" ;;
      reviewer_model=*) reviewer_model="${kv#reviewer_model=}" ;;
      "") [[ "$line" =~ ^[[:space:]]*[A-Za-z_][A-Za-z0-9_]*= ]] && echo "config: line $n ignored: $line" >&2 ;;
    esac
  done < "$cfg"
}
load_config "${TW_CONFIG_FILE:-$TW_DIR/config}"

trunk() { "$TW_SCRIPTS/trunk.sh"; }

# slugify <text>: lowercase, non-alphanumerics to '-', trimmed, at most 40 chars.
slugify() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9]+/-/g; s/^-+//; s/-+$//' \
    | cut -c1-40 | sed -E 's/-+$//'
}

# glob_match <path> <pattern>: shell pattern where '*' spans '/'. A pattern naming a
# directory (with or without a trailing '/') matches everything under it.
glob_match() {
  local p="$1" pat="$2"
  pat="${pat//\*\*/*}"
  pat="${pat%/}"
  # shellcheck disable=SC2254
  case "$p" in $pat) return 0 ;; esac
  # shellcheck disable=SC2254
  case "$p" in $pat/*) return 0 ;; esac
  return 1
}

# match_any <path> <pattern>...: 0 when any pattern matches.
match_any() {
  local p="$1" pat; shift
  for pat in "$@"; do glob_match "$p" "$pat" && return 0; done
  return 1
}

# normalize < text: every body a script reads — an issue, a review — goes through this
# once at entry. Carriage returns (a body typed in GitHub's web editor) and trailing
# whitespace on each line are removed, so no reader can miss on line endings.
normalize() { sed -e 's/\r$//' -e 's/[[:space:]]*$//'; }

# section <heading> < markdown: the body of the first '## <heading>' section. Tolerates
# an un-normalised body too: a carriage return or trailing spaces on the heading.
section() {
  awk -v h="## $1" '
    { sub(/\r$/, "") }
    { t = $0; sub(/[[:space:]]+$/, "", t) }
    t == h { on = 1; next }
    /^## / { if (on) exit }
    on { print }'
}

# count_sections <heading> < markdown: how many '## <heading>' lines there are.
count_sections() { normalize | grep -c "^## $1\$" || true; }

repo_nwo() { gh repo view --json nameWithOwner -q .nameWithOwner; }

# pr_for_task <id> [state]: prints the number of the PR whose head is wip/<id>-*.
# Exit 0 = exactly one, 1 = none, 3 = more than one (all printed).
pr_for_task() {
  local id="$1" state="${2:-open}" nums n
  nums=$(gh pr list --state "$state" --limit 200 --json number,headRefName \
    --jq ".[] | select(.headRefName | test(\"^wip/${id}-\")) | .number")
  n=$(printf '%s\n' "$nums" | grep -c . || true)
  [ "$n" -eq 0 ] && return 1
  printf '%s\n' "$nums"
  [ "$n" -eq 1 ] && return 0
  return 3
}

# blockers_json <id>: [{number,state,stateReason,title}] — GraphQL, since
# `gh issue view --json blockedBy` omits stateReason (needed to tell completed from cancelled).
blockers_json() {
  local nwo; nwo=$(repo_nwo)
  gh api graphql -f query='query($o:String!,$n:String!,$num:Int!){repository(owner:$o,name:$n){issue(number:$num){blockedBy(first:100){nodes{number state stateReason title}}}}}' \
    -F o="${nwo%/*}" -F n="${nwo#*/}" -F num="$1" --jq '.data.repository.issue.blockedBy.nodes'
}

# open_blockers <id>: prints "#n title (state/reason)" for every blocker not closed as
# completed. Exit 0 when none.
open_blockers() {
  blockers_json "$1" | jq -r '.[] | select(.state != "CLOSED" or .stateReason != "COMPLETED")
    | "#\(.number) \(.title) (\(.state)/\(.stateReason // "open"))"' | grep . && return 1
  return 0
}

# children_json <id>: [{number,title,state,stateReason}] — the issue's sub-issues, with
# stateReason so a child closed as completed is told from one cancelled.
children_json() {
  local nwo; nwo=$(repo_nwo)
  gh api graphql -f query='query($o:String!,$n:String!,$num:Int!){repository(owner:$o,name:$n){issue(number:$num){subIssues(first:100){nodes{number state stateReason title}}}}}' \
    -F o="${nwo%/*}" -F n="${nwo#*/}" -F num="$1" --jq '.data.repository.issue.subIssues.nodes'
}

# integration_branch <parent-id>: where an initiative's children land. The parent
# relation is read from the child's own issue (its parent field), never from a label.
integration_branch() { echo "wip/$1-integration"; }

# review_verdict <reviews-json> <head-committed-at>: reads the latest review and prints
#   verdict: ready|not-ready|none   isolation: <line or none>   fresh: yes|no
# followed by the review's "## Pending human checks" section, then its medium and low
# findings ("open-findings:"), which do not block but are restated at the merge gate so
# confirming means the human saw them. A section the review lacks is reported as
# "unknown", never as "none" — the gate blocks on unknown (review_blocks).
review_verdict() {
  local reviews="$1" head_time="$2" latest body at
  latest=$(printf '%s' "$reviews" | jq -c 'map(select(.body | test("readiness: *(ready|not-ready)"))) | sort_by(.submittedAt) | last // empty')
  if [ -z "$latest" ]; then echo "verdict: none"; echo "isolation: none"; echo "fresh: no"; echo "pending: none"; echo "open-findings: none"; return; fi
  body=$(printf '%s' "$latest" | jq -r .body | normalize)
  at=$(printf '%s' "$latest" | jq -r .submittedAt)
  echo "verdict: $(printf '%s' "$body" | grep -oE 'readiness: *(ready|not-ready)' | tail -1 | sed 's/readiness: *//')"
  echo "isolation: $(printf '%s' "$body" | grep -oE '^isolation:.*' | head -1 | sed 's/^isolation: *//' || true)"
  if [ -n "$head_time" ] && [ "$at" \> "$head_time" ]; then echo "fresh: yes"; else echo "fresh: no"; fi
  local pending
  pending=$(printf '%s\n' "$body" | section "Pending human checks" | sed '/^readiness:/,$d' | grep -v '^[[:space:]]*$' || true)
  if [ -z "$pending" ]; then echo "pending: unknown"; else echo "pending:"; printf '%s\n' "$pending" | sed 's/^/  /'; fi
  if [ "$(printf '%s\n' "$body" | count_sections Findings)" -eq 0 ]; then echo "open-findings: unknown"; return; fi
  local findings
  findings=$(printf '%s\n' "$body" | section "Findings" | awk '
    /^### /{ sev = tolower($0); sub(/^### */, "", sev); sub(/[[:space:]]+$/, "", sev); next }
    (sev == "medium" || sev == "low") && /^- / && tolower($0) !~ /^- *\(?none\)?\.? *$/ { print "  " sev ": " substr($0, 3) }')
  if [ -z "$findings" ]; then echo "open-findings: none"; else echo "open-findings:"; printf '%s\n' "$findings"; fi
}

# review_blocks <required: yes|no> <id> < review_verdict-output: the ship gate's review
# rules, one "BLOCKED: ..." line per failed rule (a "note: ..." line where a rule only
# informs). Exit 0 when nothing blocks. Kept out of gate.sh so the rules are testable
# without a tracker: no review at all blocks only a protected diff; not-ready always
# blocks; a ready review older than the head blocks a protected diff and is a note
# otherwise; a same-session review blocks a protected diff; unknown pending checks or
# unknown findings block whenever a review exists — an absent section is never "none".
review_blocks() {
  local required="$1" id="$2" rv verdict iso fresh rc=0
  rv=$(cat)
  verdict=$(printf '%s\n' "$rv" | sed -n 's/^verdict: //p'); iso=$(printf '%s\n' "$rv" | sed -n 's/^isolation: //p'); fresh=$(printf '%s\n' "$rv" | sed -n 's/^fresh: //p')
  b() { echo "BLOCKED: $*"; rc=1; }
  if [ "$required" = yes ]; then
    [ "$verdict" = none ] && b "protected diff with no cold review — run /t-review $id"
    [ "$verdict" = ready ] && [ "$fresh" = no ] && b "the ready review is older than the head commit — run /t-review $id again"
    [ "$verdict" = ready ] && case "$iso" in *"same session"*) b "review isolation is 'same session'; a protected diff needs a fresh session or subagent review" ;; esac
  else
    [ "$verdict" = ready ] && [ "$fresh" = no ] && echo "note: the ready review predates the head commit"
  fi
  [ "$verdict" = not-ready ] && b "latest review is not-ready — run /t-work $id to address it"
  if [ "$verdict" != none ]; then
    printf '%s\n' "$rv" | grep -q '^pending: unknown$' && b "the review has no '## Pending human checks' section, so its checks are unknown — ask the reviewer to add it (\"none\" when there are none)"
    printf '%s\n' "$rv" | grep -q '^open-findings: unknown$' && b "the review has no '## Findings' section, so its findings are unknown — ask the reviewer to add it (\"- none\" under each severity when there are none)"
  fi
  return "$rc"
}
