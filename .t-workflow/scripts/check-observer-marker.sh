#!/usr/bin/env bash
# Validates the observer correlation marker /t-open appends to a new issue's body
# (docs/adapters/OBSERVER.md, issue #143): a single-line HTML comment carrying
# identifiers only — a task or initiative id, an optional parent-initiative id, and an
# optional origin system/url pair — never scope, acceptance criteria, or a review
# decision. Read docs/adapters/OBSERVER.md's own grammar section before touching this
# file; this script is its executable twin, the same "doc is the authority, script is
# its machine-readable form" split CONSTITUTION.md §3 already uses for protected-paths.sh.
#
# This script is wired into no gate — same posture as check-preview-evidence.sh:
# reading or emitting the marker is convenience for an external observer, never
# required by /t-ship, /t-review, or CI. It exists so /t-open's own output, and this
# repo's plumbing-test.sh, can be checked against the contract.
#
# Grammar (docs/adapters/OBSERVER.md):
#   <!-- t-workflow:v1 (task=<int>|initiative=<int>) [parent=<int>]
#        [origin-system="<text>" origin-url=<url>] -->
#   on one line, prefix through suffix. A value with no spaces may be given bare; one
#   with spaces must be double-quoted.
#   - Exactly one of `task`/`initiative` — an issue is never both.
#   - `parent` only valid alongside `task` (a child names its initiative); never
#     alongside `initiative`.
#   - `origin-system`/`origin-url`: both present or neither — the same "both fields or
#     neither" rule docs/architecture/external-origin.md already uses for the
#     human-facing `## Origin` section this marker echoes.
#   - No key outside this set.
# A malformed marker is flagged distinctly from no marker at all: this repository's own
# /t-open is the marker's only writer today, so a malformed one is a defect in what we
# wrote, never unreliable external input — ADR-010 §D7's fail-toward-absent posture
# governs input arriving FROM outside t-workflow, not our own output.
#
# Known simplification: this validates the recognized fields and their pairing rules;
# it does not certify that no stray text sits between valid tokens (safely removing an
# arbitrary already-matched substring — one that may contain URL characters that are
# also shell glob metacharacters — cannot be done with plain pattern-substitution
# without misparsing some URLs). The marker's only writer is our own /t-open, so this is
# an accepted boundary, not a gap this script's callers rely on closing.
#
# Usage: .t-workflow/scripts/check-observer-marker.sh <body-text-file>
#   <body-text-file> holds an issue or PR body (or any text a marker might be embedded
#   in) — plain text, never JSON.
# Output (stdout): one JSON object —
#   {"present": bool, "valid": bool, "errors": [<string>, ...],
#    "task": <int>|null, "initiative": <int>|null, "parent": <int>|null,
#    "origin": {"system":,"url":} | null}
# Exit 0 = no marker present, or a marker present and valid; 1 = a marker is present but
#          malformed (errors[] is non-empty); 2 = bad usage.
set -uo pipefail

usage() {
  echo "usage: check-observer-marker.sh <body-text-file>" >&2
  exit 2
}

file="${1:-}"
[ -n "$file" ] && [ -f "$file" ] || usage

empty_result() {
  jq -n --argjson present "$1" --argjson valid "$2" --argjson errors "$3" \
    '{present: $present, valid: $valid, errors: $errors, task: null, initiative: null, parent: null, origin: null}'
}

# Exactly-one-line match: the whole comment, prefix through suffix, on one line.
matches=$(grep -cE '^<!-- t-workflow:v1 .* -->[[:space:]]*$' "$file")

if [ "$matches" -eq 0 ]; then
  empty_result false true '[]'
  exit 0
fi

if [ "$matches" -gt 1 ]; then
  empty_result true false '["multiple observer markers found — exactly one is allowed"]'
  exit 1
fi

line=$(grep -E '^<!-- t-workflow:v1 .* -->[[:space:]]*$' "$file")
# Strip the fixed prefix/suffix and trailing whitespace, leaving only the
# space-separated key=value tokens.
inner="${line#<!-- t-workflow:v1 }"
inner="${inner%-->}"
inner="$(printf '%s' "$inner" | sed -E 's/[[:space:]]+$//')"

# Tokenize: a quoted value ("..." — may contain spaces) or a bare value (no spaces/quotes).
tokens=()
while IFS= read -r tok; do
  [ -n "$tok" ] && tokens+=("$tok")
done < <(printf '%s' "$inner" | grep -oE '[a-zA-Z][a-zA-Z0-9-]*="[^"]*"|[a-zA-Z][a-zA-Z0-9-]*=[^[:space:]"]+')

errors=()
task="" initiative="" parent="" origin_system="" origin_url=""
for tok in "${tokens[@]}"; do
  key="${tok%%=*}"
  val="${tok#*=}"
  if [[ "$val" == \"*\" ]]; then val="${val:1:${#val}-2}"; fi
  case "$key" in
    task) task="$val" ;;
    initiative) initiative="$val" ;;
    parent) parent="$val" ;;
    origin-system) origin_system="$val" ;;
    origin-url) origin_url="$val" ;;
    *) errors+=("unrecognized key '$key'") ;;
  esac
done

[ -n "$task" ] && [ -n "$initiative" ] && errors+=("both 'task' and 'initiative' present — an issue is never both")
[ -z "$task" ] && [ -z "$initiative" ] && errors+=("neither 'task' nor 'initiative' present — exactly one is required")

task_num=""; [[ -n "$task" && "$task" =~ ^[0-9]+$ ]] && task_num="$task"
[ -n "$task" ] && [ -z "$task_num" ] && errors+=("'task' is not a positive integer")

initiative_num=""; [[ -n "$initiative" && "$initiative" =~ ^[0-9]+$ ]] && initiative_num="$initiative"
[ -n "$initiative" ] && [ -z "$initiative_num" ] && errors+=("'initiative' is not a positive integer")

parent_num=""; [[ -n "$parent" && "$parent" =~ ^[0-9]+$ ]] && parent_num="$parent"
[ -n "$parent" ] && [ -z "$parent_num" ] && errors+=("'parent' is not a positive integer")
[ -n "$parent" ] && [ -n "$initiative" ] && errors+=("'parent' present alongside 'initiative' — only a task names its parent")

{ [ -n "$origin_system" ] && [ -z "$origin_url" ]; } && errors+=("'origin-system' present without 'origin-url'")
{ [ -z "$origin_system" ] && [ -n "$origin_url" ]; } && errors+=("'origin-url' present without 'origin-system'")

valid=true
[ "${#errors[@]}" -gt 0 ] && valid=false

errors_json='[]'
if [ "${#errors[@]}" -gt 0 ]; then
  errors_json=$(printf '%s\n' "${errors[@]}" | jq -R '.' | jq -s '.')
fi

jq -n \
  --arg task "$task_num" --arg initiative "$initiative_num" --arg parent "$parent_num" \
  --arg osys "$origin_system" --arg ourl "$origin_url" \
  --argjson valid "$valid" \
  --argjson errors "$errors_json" \
  '
  {
    present: true,
    valid: $valid,
    errors: $errors,
    task: (if $task == "" then null else ($task | tonumber) end),
    initiative: (if $initiative == "" then null else ($initiative | tonumber) end),
    parent: (if $parent == "" then null else ($parent | tonumber) end),
    origin: (if $osys == "" or $ourl == "" then null else {system: $osys, url: $ourl} end)
  }
  '

[ "$valid" = "true" ]
exit $?
