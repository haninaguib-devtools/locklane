#!/usr/bin/env bash
# One-shot read phase for /t-status: replaces five separate sequential tracker/forge
# queries (initiatives, tasks, PRs, local git state, cancellations) with one script
# invocation emitting a single JSON blob, per issue #65. Strictly read-only — no
# gh/git subcommand here mutates anything, matching t-status's own "never writes"
# invariant (AGENTS.md, .claude/skills/t-status/SKILL.md).
#
# This script only fetches and assembles already-mechanical facts (raw issue/PR data,
# a computed CI bucket, a computed latest review, a computed truncation flag). It makes
# no refusal or judgment call — blocked/unblocked, protected-surface, scope-overlap, and
# intent-drift decisions all stay in t-status's own prose, reading the fields below.
#
# Usage: .t-workflow/scripts/status-snapshot.sh
#   No arguments. Run from anywhere inside the repository, with `gh` authenticated.
# Exit 0 = one JSON object printed on stdout. Exit 2 = bad usage (no live call made).
set -euo pipefail

if [ "$#" -gt 0 ]; then
  echo "usage: .t-workflow/scripts/status-snapshot.sh   (no arguments)" >&2
  exit 2
fi

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
cd "$root"

work=$(mktemp -d "${TMPDIR:-/tmp}/status-snapshot.XXXXXX")
trap 'rm -rf "$work"' EXIT HUP INT TERM

# --- issues: one bulk call serves both initiatives and tasks (ADR-003's blockedBy) ---
issue_limit=1000
gh issue list --state open --limit "$issue_limit" \
  --json number,title,body,labels,blockedBy,updatedAt,subIssuesSummary \
  > "$work/issues-open.json"

gh issue list --state closed --label cancelled --limit 100 --json number,title \
  > "$work/issues-cancelled.json"

# --- PRs: one all-state call carries files/reviews/checks for every PR at once ---
pr_limit=200
gh pr list --state all --limit "$pr_limit" \
  --json number,title,state,isDraft,headRefName,url,createdAt,mergeable,mergeStateStatus,reviews,files,statusCheckRollup \
  > "$work/prs-all.json"

# --- local git state: no gh call, just this checkout and its worktrees ---
branch=$(git rev-parse --abbrev-ref HEAD)
if [ -z "$(git status --porcelain)" ]; then clean=true; else clean=false; fi
git branch --list 'wip/*' --format='%(refname:short)' > "$work/local-branches.txt"
git worktree list --porcelain | awk '
  /^worktree / { path = $2; branch = "" }
  /^branch /   { branch = $2; sub("^refs/heads/", "", branch) }
  /^$/         { if (path != "" && branch != "") print path "\t" branch; path = ""; branch = "" }
  END          { if (path != "" && branch != "") print path "\t" branch }
' > "$work/worktrees.tsv"

jq -Rn '
  [inputs | select(length > 0) | split("\t") | {path: .[0], branch: .[1]}]
' "$work/worktrees.tsv" > "$work/worktrees.json"

jq -Rn '[inputs | select(length > 0)]' "$work/local-branches.txt" > "$work/local-branches.json"

jq -n \
  --slurpfile issues "$work/issues-open.json" \
  --slurpfile cancelled "$work/issues-cancelled.json" \
  --slurpfile prs "$work/prs-all.json" \
  --slurpfile localBranches "$work/local-branches.json" \
  --slurpfile worktrees "$work/worktrees.json" \
  --arg branch "$branch" \
  --argjson clean "$clean" \
  --argjson issueLimit "$issue_limit" \
  --argjson prLimit "$pr_limit" \
  '
  ($issues[0]) as $issues |
  ($cancelled[0]) as $cancelled |
  ($prs[0]) as $prs |
  ($localBranches[0]) as $localBranches |
  ($worktrees[0]) as $worktrees |

  # a PR whose head branch matches wip/<id>-* — for correlating a local/worktree branch
  def pr_for_branch($b): $prs | map(select(.headRefName == $b)) | sort_by(.createdAt) | last;

  # CI contract (forge:pr-checks): distinguish "no CI configured" from failing/pending.
  # Enumerate every terminal non-passing conclusion the checkRun API defines, not just
  # FAILURE — CANCELLED/TIMED_OUT/ACTION_REQUIRED/STARTUP_FAILURE/STALE are all
  # `status: COMPLETED` too, so checking only FAILURE let those fall through to "pass".
  def ci_state:
    ["FAILURE", "CANCELLED", "TIMED_OUT", "ACTION_REQUIRED", "STARTUP_FAILURE", "STALE"] as $failing |
    if (.statusCheckRollup | length) == 0 then "none configured"
    elif ([.statusCheckRollup[] | select(.status == "COMPLETED" and ([.conclusion] | inside($failing)))] | length) > 0 then "fail"
    elif ([.statusCheckRollup[] | select(.status != "COMPLETED")] | length) > 0 then "pending"
    else "pass" end;

  # forge:pr-reviews contract: the caller finds the latest review by submittedAt.
  def latest_review:
    (.reviews | sort_by(.submittedAt) | last) as $r |
    if $r == null then null else {body: $r.body, submittedAt: $r.submittedAt, state: $r.state} end;

  {
    initiatives: [$issues[] | select([.labels[].name] | index("initiative") != null)
      | {number, title, subIssuesSummary}],

    tasks: {
      truncated: (($issues | length) == $issueLimit),
      items: [$issues[] | select([.labels[].name] | index("initiative") == null)
        | {number, title, body, labels: [.labels[].name], blockedBy: .blockedBy.nodes, updatedAt}]
    },

    prs: {
      truncated: (($prs | length) == $prLimit),
      open: [$prs[] | select(.state == "OPEN")
        | {number, title, isDraft, headRefName, url, createdAt, mergeable, mergeStateStatus,
           files: [.files[].path], ciState: ci_state, latestReview: latest_review}]
    },

    local: {
      branch: $branch,
      clean: $clean,
      localWipBranches: [$localBranches[]
        | {name: ., pr: (pr_for_branch(.) | if . == null then null else {number, state, url} end)}],
      worktrees: [$worktrees[]
        | {path, branch, pr: (pr_for_branch(.branch) | if . == null then null else {number, state, url} end)}]
    },

    cancellations: {
      truncated: (($cancelled | length) == 100),
      items: $cancelled
    }
  }
  '
