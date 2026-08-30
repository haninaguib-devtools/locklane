#!/usr/bin/env bash
# One-shot read phase for /t-review steps 2-3: replaces separate tracker/forge calls
# (the issue, the PR diff, the PR's view/files, local git state) with one script
# invocation emitting a single JSON blob, per issue #73 — the same treatment #69 gave
# /t-status, /t-drive, and /t-ship via status-snapshot.sh.
#
# This script only fetches already-mechanical facts. It makes no refusal or judgment
# call — isolation, protected-surface, scope/record/constitution, and readiness-verdict
# decisions all stay in t-review's own prose, reading the fields below. Resolving <pr>
# (forge:pr-find-by-task) and the isolation decision happen before this script runs
# (t-review step 1) and are not folded into it.
#
# Usage: .t-workflow/scripts/review-snapshot.sh <task-id> <pr>
#   Run from anywhere inside the repository, with `gh` authenticated.
# Exit 0 = one JSON object printed on stdout. Exit 2 = bad usage (no live call made).
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "usage: .t-workflow/scripts/review-snapshot.sh <task-id> <pr>" >&2
  exit 2
fi

task_id="$1"
pr="$2"

case "$task_id" in
  ''|*[!0-9]*) echo "usage: <task-id> must be numeric, got '$task_id'" >&2; exit 2 ;;
esac
case "$pr" in
  ''|*[!0-9]*) echo "usage: <pr> must be numeric, got '$pr'" >&2; exit 2 ;;
esac

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
cd "$root"

work=$(mktemp -d "${TMPDIR:-/tmp}/review-snapshot.XXXXXX")
trap 'rm -rf "$work"' EXIT HUP INT TERM

# --- tracker:view <task-id> ---
gh issue view "$task_id" --json number,title,body,state,labels,parent,subIssuesSummary \
  > "$work/issue.json"

# --- forge:pr-diff <pr>: raw unified-diff text, not JSON ---
gh pr diff "$pr" > "$work/diff.txt"

# --- forge:pr-view <pr>, plus files in the same call (satisfies forge:pr-files too) ---
gh pr view "$pr" \
  --json url,isDraft,headRefName,headRefOid,mergeable,mergeStateStatus,commits,files \
  > "$work/pr.json"

# --- local git state: no gh call ---
head=$(git rev-parse HEAD)
status=$(git status --porcelain)
if [ -z "$status" ]; then clean=true; else clean=false; fi

jq -n \
  --slurpfile issue "$work/issue.json" \
  --slurpfile pr "$work/pr.json" \
  --rawfile diff "$work/diff.txt" \
  --arg head "$head" \
  --arg status "$status" \
  --argjson clean "$clean" \
  '
  ($issue[0]) as $issue |
  ($pr[0]) as $pr |

  {
    issue: {
      number: $issue.number,
      title: $issue.title,
      body: $issue.body,
      state: $issue.state,
      labels: [$issue.labels[].name],
      parent: $issue.parent,
      subIssuesSummary: $issue.subIssuesSummary
    },

    pr: {
      url: $pr.url,
      isDraft: $pr.isDraft,
      headRefName: $pr.headRefName,
      headRefOid: $pr.headRefOid,
      mergeable: $pr.mergeable,
      mergeStateStatus: $pr.mergeStateStatus,
      commits: $pr.commits,
      files: [$pr.files[].path]
    },

    diff: $diff,

    local: {
      head: $head,
      clean: $clean,
      status: $status
    }
  }
  '
