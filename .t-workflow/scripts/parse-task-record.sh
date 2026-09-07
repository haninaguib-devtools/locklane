#!/usr/bin/env bash
# Turns a task record's `## Verification`/`## Feedback` markdown (docs/tasks/TEMPLATE.md,
# docs/architecture/verification.md, docs/architecture/feedback-pass.md) — and,
# optionally, the task's own issue body's `## Plan` -> `verification:` list — into the
# small JSON shape /t-status's own collaboration-state derivation needs (issue #147).
#
# Pure text in, JSON out — like check-blocker-gate.sh/check-review-gate.sh/
# check-verification-gate.sh, this script never touches git or the forge itself, so it
# stays fixture-testable. status-snapshot.sh is what actually reads a record's blob off
# a task's own branch (`git show origin/<branch>:<path>`, never a checkout) and hands
# the resulting text to this script; it is also what computes the live `git diff
# --name-only <revision> <head>` a `scope:` exemption needs (docs/architecture/
# verification.md's own formula) — this script only extracts the globs, never runs a
# diff itself.
#
# Why the issue body is a second, optional input: docs/tasks/TEMPLATE.md's own
# `## Verification` section (see its own header comment) does not carry a `scope:`
# line — that field lives only on the *plan's* `verification:` list, on the issue
# (`.claude/skills/t-plan/SKILL.md`'s own Plan template). This script cross-references
# the two by `role`, in the plan's own list order, mirroring exactly how a human reading
# both documents would. Given no issue-body file, every entry's `scope` comes back `[]`
# (meaning "no scope known" — treated the same as "no scope declared": stale by default
# whenever the revision differs, the fail-toward-absent posture
# docs/architecture/verification.md documents).
#
# Usage: .t-workflow/scripts/parse-task-record.sh <record-file> [<issue-body-file>]
# Output (stdout): {"verification": [{"role":,"required":,"state":,"revision":,
#                                       "scope": [<globs>]}, ...],
#                    "feedbackLastClassification": "<string>" | null}
#   `revision` is null when the record reads "none yet" (or the field could not be
#   found) — never a guessed value. A record whose `## Verification` or `## Feedback`
#   section reads plain "none" (or is entirely absent) yields `[]` / `null` — the
#   documented "nothing to check" case, never an error.
# Exit 0 = parsed (possibly empty); 2 = bad usage — no record file given, or it does not
#   exist. Malformed *content* is never a usage error: this script degrades toward
#   fewer/emptier fields rather than refusing, since a record is prose a human writes
#   and small formatting drift must not make /t-status crash (ADR-010 §D7).
set -uo pipefail

usage() {
  echo "usage: parse-task-record.sh <record-file> [<issue-body-file>]" >&2
  exit 2
}

record_file="${1:-}"
issue_file="${2:-}"
[ -n "$record_file" ] && [ -f "$record_file" ] || usage
if [ -n "$issue_file" ] && [ ! -f "$issue_file" ]; then
  echo "parse-task-record.sh: issue-body file '$issue_file' does not exist" >&2
  exit 2
fi

# --- ## Verification: one TSV row per entry (role, required, state, revision) --------
verif_tsv=$(awk '
  BEGIN { insec = 0; started = 0 }
  function flush() { if (started) printf "%s\t%s\t%s\t%s\n", role, required, state, revision }
  $0 == "## Verification" { insec = 1; next }
  insec && /^## / { flush(); insec = 0; started = 0; next }
  insec && /^- role:/ {
    flush()
    started = 1
    line = $0
    sub(/^- role: */, "", line)
    idx = index(line, " — required: ")
    if (idx > 0) { role = substr(line, 1, idx - 1); required = substr(line, idx + length(" — required: ")) }
    else { role = line; required = "" }
    state = ""; revision = ""
    next
  }
  insec && started && index($0, "state:") > 0 && $0 !~ /revision/ {
    line = $0; sub(/^[ \t]*state: */, "", line); state = line; next
  }
  insec && started && index($0, "evidence:") > 0 {
    idx = index($0, "revision: `")
    if (idx > 0) {
      rest = substr($0, idx + length("revision: `"))
      endidx = index(rest, "`")
      if (endidx > 0) revision = substr(rest, 1, endidx - 1)
    }
    next
  }
  END { flush() }
' "$record_file")

verification_json=$(printf '%s\n' "$verif_tsv" | jq -Rn '
  [inputs | select(length > 0) | split("\t") |
    {role: .[0],
     required: (.[1] == "true"),
     state: .[2],
     revision: (if (.[3] == "" or (.[3] | ascii_downcase) == "none yet") then null else .[3] end)}]
')

# --- ## Feedback: only the LAST entry's classification matters for /t-status ----------
feedback_last=$(awk '
  BEGIN { insec = 0; started = 0; classification = "" }
  $0 == "## Feedback" { insec = 1; next }
  insec && /^## / { exit }
  insec && /^- reference:/ { started = 1; classification = ""; next }
  insec && started && index($0, "classification:") > 0 {
    line = $0; sub(/^[ \t]*classification: */, "", line); classification = line; next
  }
  END { if (started) print classification }
' "$record_file")
# Trim any trailing/leading whitespace the awk extraction left in place.
feedback_last=$(printf '%s' "$feedback_last" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')

# --- the issue's own ## Plan -> verification: list, for scope: globs by role ----------
scope_tsv=""
if [ -n "$issue_file" ]; then
  scope_tsv=$(awk '
    BEGIN { inplan = 0; invlist = 0; started = 0 }
    function flush() { if (started) printf "%s\t%s\n", role, scope }
    $0 == "## Plan" { inplan = 1; next }
    inplan && /^## / { flush(); exit }
    inplan && /^verification:/ { invlist = 1; next }
    inplan && invlist && /^  - role:/ {
      flush()
      started = 1
      line = $0; sub(/^  - role: */, "", line); role = line; scope = ""
      next
    }
    inplan && invlist && started && index($0, "scope:") > 0 {
      line = $0; sub(/^[ \t]*scope: */, "", line); scope = line; next
    }
    inplan && invlist && started && /^[a-zA-Z_]+:/ && $0 !~ /^[ \t]/ { flush(); invlist = 0; started = 0 }
    END { flush() }
  ' "$issue_file")
fi

scope_json=$(printf '%s\n' "$scope_tsv" | jq -Rn '
  [inputs | select(length > 0) | split("\t") | {role: .[0], scope: .[1]}]
')

# --- merge scope globs into each verification entry, matched by role -----------------
jq -n \
  --argjson verification "$verification_json" \
  --argjson scopes "$scope_json" \
  --arg feedback "$feedback_last" \
  '
  def globs_for($role):
    ($scopes | map(select(.role == $role)) | first) as $s |
    if $s == null or $s.scope == "" then []
    else ($s.scope | split(",") | map(gsub("^\\s+|\\s+$"; ""))) end;

  {
    verification: [$verification[] | . + {scope: globs_for(.role)}],
    feedbackLastClassification: (if $feedback == "" then null else $feedback end)
  }
  '
