#!/usr/bin/env bash
# Scheduled companion to .github/workflows/stale-branches.yml (issue #39): confirms a
# merged or closed PR's head branch actually got deleted on `origin`. Branch deletion
# depends on the exact command a skill (or a human) ran actually carrying the active
# backend's branch-deletion flag (docs/adapters/FORGE.md's forge:pr-merge/
# forge:pr-close) — an invocation that omits it leaves the branch lingering with
# nothing surfacing the miss until someone notices the branch list by eye (task #36).
#
# Report-only: never deletes anything (issue #39 non-goal). Exit 0 = no stale branch
# found; 1 = at least one reported, listed on stdout.
#
# Requires: gh (authenticated), jq. Run from the repository root.
set -uo pipefail

# A PR merged/closed more recently than this is not flagged yet — the forge's branch
# deletion can lag its own API response by a few seconds, and this avoids false
# positives on a PR that just merged.
GRACE_SECONDS="${GRACE_SECONDS:-3600}"

# Portable ISO-8601 -> epoch: GNU `date -d` (CI runners, Linux) first, BSD/macOS
# `date -j` (local runs) as fallback.
to_epoch() {
  date -u -d "$1" +%s 2>/dev/null || date -u -j -f '%Y-%m-%dT%H:%M:%SZ' "$1" +%s
}

now=$(date -u +%s)

prs=$(gh pr list --state all --limit 200 \
  --json number,headRefName,state,mergedAt,closedAt,url)

count=$(printf '%s' "$prs" | jq 'length')
if [ "$count" -ge 200 ]; then
  echo "WARNING: gh pr list returned $count PRs, at the page limit — this scan may be" >&2
  echo "  incomplete. Raise --limit rather than trusting a clean result." >&2
fi

stale=0
while IFS=$'\t' read -r number head state mergedAt closedAt url; do
  [ -z "$number" ] && continue

  # Only the workflow's own branch-naming convention is this check's business
  # (ADR-001 §D2/§D4) — anything else was never promised deletion by these skills.
  case "$head" in
    wip/*|fix/*) ;;
    *) continue ;;
  esac

  case "$state" in
    MERGED) ts="$mergedAt" ;;
    CLOSED) ts="$closedAt" ;;
    *) continue ;;  # OPEN — not merged or closed yet
  esac
  if [ -z "$ts" ] || [ "$ts" = "null" ]; then
    continue
  fi

  event_epoch=$(to_epoch "$ts") || continue
  age=$(( now - event_epoch ))
  if [ "$age" -lt "$GRACE_SECONDS" ]; then
    continue
  fi

  if gh api "repos/{owner}/{repo}/branches/$head" >/dev/null 2>&1; then
    echo "STALE: PR #$number ($state) — branch '$head' still exists on origin — $url"
    stale=1
  fi
done < <(printf '%s' "$prs" | jq -r '.[] | [.number, .headRefName, .state, .mergedAt, .closedAt, .url] | @tsv')

if [ "$stale" -eq 0 ]; then
  echo "OK: no stale wip/*/fix/* branches found (grace period ${GRACE_SECONDS}s)."
fi
exit "$stale"
