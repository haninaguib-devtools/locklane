#!/usr/bin/env bash
# Classifies a single task's collaboration state for /t-status (issue #147), mirroring
# the precedence docs/architecture/collaboration-state.md documents. Pure fixtures
# throughout — like check-blocker-gate.sh/check-review-gate.sh/check-verification-gate.sh,
# this script never touches git or the forge itself; the caller (status-snapshot.sh's
# own preprocessing plus /t-status's own procedure) assembles the small JSON shape
# below from already-fetched fields, and this script's only job is the one ordering
# decision none of those individual fields can answer alone.
#
# This is display, never a gate (ADR-010 §D2-D4, §D7): nothing here blocks a merge or
# a review; /t-ship and /t-drive keep their own independent gates
# (check-verification-gate.sh, check-review-gate.sh, check-blocker-gate.sh) unchanged
# and unconsulted by this script.
#
# Input shape — one task, always describing a task that already has an open PR (a
# task with no PR yet is classified by /t-status's own existing prose: blocked / ready
# to pick up / stalled — this script is never called for it):
#
#   {"blocked":       "none"|"open"|"cancelled",
#    "ciState":       "pass"|"fail"|"pending"|"none configured",
#    "review":        null | {"readiness": "ready"|"not-ready"|null, "current": true|false},
#    "feedback":      {"pendingScopeExpansion": true|false},
#    "verification":  [{"role":, "required":, "state":, "stale":}, …],
#    "previewReady":  null | true | false}
#
# `review: null` means no review has ever landed on the PR. `review.current` is
# `submittedAt` compared against the PR's head commit time, computed by the caller the
# same way check-review-gate.sh already does. `verification` is exactly the shape
# check-verification-gate.sh already takes (this script does not re-derive `stale` —
# the caller computes it once, per docs/architecture/verification.md's formula, and
# both this script and check-verification-gate.sh trust it). `previewReady` is `null`
# when no preview evidence was found for the task's current commit at all (an optional
# external system that was never wired up — Done-when's "missing optional external
# systems do not prevent status reporting"), `true`/`false` when evidence was found and
# its `status` is/isn't `ready` (docs/adapters/PREVIEW.md).
#
# Output: one line of JSON on stdout, `{"state": "<slug>", "detail": "<human-readable
# reason>"}` — `/t-status`'s own prose reads `state`, never re-derives it, and reports
# `detail` (or its own richer wording) to the maintainer.
#
# States, most to least urgent — the first that matches wins, documented in full in
# docs/architecture/collaboration-state.md:
#   blocked > checks-failing > awaiting-review > awaiting-renewed-review >
#   changes-requested > processing-feedback > awaiting-preview |
#   awaiting-external-verification > verified-ready-for-ship | awaiting-ship
#
# Usage: .t-workflow/scripts/derive-task-state.sh <task-json-file>
# Exit 0 = classified, JSON printed; 2 = bad usage or malformed input — an unreadable
# or malformed shape must never read as any particular state (ADR-010 §D7 fail-toward-
# absent), so this script refuses rather than guessing.
set -uo pipefail

usage() {
  echo "usage: derive-task-state.sh <task-json-file>" >&2
  exit 2
}

file="${1:-}"
[ -n "$file" ] && [ -f "$file" ] || usage

# --- shape validation: fail closed on anything unrecognized ---------------------------
jq -e '
  (.blocked // "none") as $blocked |
  (.ciState // "pass") as $ci |
  (.feedback.pendingScopeExpansion // false) as $fb |
  (.verification // []) as $verif |
  (.review) as $review |
  (.previewReady) as $pr |
  ($blocked | IN("none","open","cancelled")) and
  ($ci | IN("pass","fail","pending","none configured")) and
  ($fb | type) == "boolean" and
  ($verif | type) == "array" and
  ($review == null or (
    (($review.readiness) as $r | ($r == null or $r == "ready" or $r == "not-ready")) and
    (($review.current) | type) == "boolean"
  )) and
  ($pr == null or ($pr | type) == "boolean") and
  (all($verif[]; (.role != null) and (.required | type) == "boolean" and
       (.state | IN("pending","verified","rejected","risk-accepted")) and
       (.stale | type) == "boolean"))
' "$file" >/dev/null 2>&1 || {
  echo "FAIL: $file is not a well-formed task-state input (see this script's own header comment for the shape)" >&2
  exit 2
}

blocked=$(jq -r '.blocked // "none"' "$file")
ci=$(jq -r '.ciState // "pass"' "$file")
has_review=$(jq -r 'if (.review // null) == null then "no" else "yes" end' "$file")
review_current=$(jq -r '.review.current // false' "$file")
review_readiness=$(jq -r '.review.readiness // "not-ready"' "$file")
pending_expansion=$(jq -r '.feedback.pendingScopeExpansion // false' "$file")
preview_ready=$(jq -r 'if .previewReady == null then "unknown" elif .previewReady == true then "true" else "false" end' "$file")

# A required entry that is unresolved, or resolved but stale — the one condition that
# keeps a task out of "ready for /t-ship" once review and feedback are clear.
blocking_verif=$(jq -r '
  [.verification[]? | select(.required == true and
     ((.state == "pending" or .state == "rejected") or
      ((.state == "verified" or .state == "risk-accepted") and .stale == true)))] | length' "$file")
blocking_stale=$(jq -r '
  [.verification[]? | select(.required == true and
     (.state == "verified" or .state == "risk-accepted") and .stale == true)] | length' "$file")
has_any_verif=$(jq -r '(.verification // []) | length' "$file")

state=""
detail=""

if [ "$blocked" = "open" ]; then
  state="blocked"; detail="blocked by an unresolved blocker outside this task"
elif [ "$blocked" = "cancelled" ]; then
  state="blocked"; detail="blocked by a blocker that was cancelled, not satisfied"
elif [ "$ci" = "fail" ]; then
  state="checks-failing"; detail="CI is failing on the PR's head commit"
elif [ "$has_review" = "no" ]; then
  state="awaiting-review"; detail="no review has landed on this PR yet"
elif [ "$review_current" != "true" ]; then
  state="awaiting-renewed-review"; detail="a later commit landed after the last review — it no longer speaks for the head commit"
elif [ "$review_readiness" != "ready" ]; then
  state="changes-requested"; detail="the latest review found unresolved blocker/high findings"
elif [ "$pending_expansion" = "true" ]; then
  state="processing-feedback"; detail="a feedback pass proposed a scope expansion and awaits human authorization and /t-plan"
elif [ "$blocking_verif" -gt 0 ]; then
  if [ "$preview_ready" = "false" ]; then
    state="awaiting-preview"
    detail="cited preview evidence for the current commit is not ready"
  else
    state="awaiting-external-verification"
    if [ "$blocking_stale" -gt 0 ]; then
      detail="a later commit could affect a previously verified/risk-accepted entry — it must be re-verified or re-accepted at the new revision"
    else
      detail="a required verification entry is still pending or was rejected"
    fi
  fi
elif [ "$has_any_verif" -gt 0 ]; then
  state="verified-ready-for-ship"; detail="every required verification entry is verified or risk-accepted at the current commit"
else
  state="awaiting-ship"; detail="reviewed and ready — awaiting /t-ship"
fi

jq -n --arg state "$state" --arg detail "$detail" '{state: $state, detail: $detail}'
