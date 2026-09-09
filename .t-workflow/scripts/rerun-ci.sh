#!/usr/bin/env bash
# Re-run the t-workflow CI run for a PR's head commit once a cold review exists: the
# run that fired on the push was red only because no review existed yet, and
# re-running it is what turns it green — one run per commit, nothing for a human to do.
#   rerun-ci.sh <pr>
#   exit 0 = re-run started, or nothing to do (said why); 1 = the re-run could not be
#   started; 2 = the PR or its runs could not be read.
set -uo pipefail
. "$(dirname "$0")/lib.sh"
pr="${1:-}"; [ -n "$pr" ] || { sed -n '2,7p' "$0" | sed 's/^# //'; exit 2; }
v=$(gh pr view "$pr" --json headRefOid,isDraft) || die "cannot read PR #$pr"
head=$(printf '%s' "$v" | jq -r .headRefOid)
if [ "$(printf '%s' "$v" | jq -r .isDraft)" = true ]; then
  echo "PR #$pr is a draft: CI runs when /t-ship marks it ready, and will see the review then"; exit 0
fi
runs=$(gh run list --workflow t-workflow --commit "$head" --json databaseId,status,conclusion) || die "cannot list runs for $head"
run=$(printf '%s' "$runs" | jq -c 'first // empty')
[ -n "$run" ] || { echo "no t-workflow run at $head; CI has not been triggered for this commit"; exit 0; }
id=$(printf '%s' "$run" | jq -r .databaseId); status=$(printf '%s' "$run" | jq -r .status); conclusion=$(printf '%s' "$run" | jq -r '.conclusion // ""')
if [ "$status" != completed ]; then
  echo "run $id at $head is still $status; if it ends red, run this again"; exit 0
fi
case "$conclusion" in
  success) echo "run $id at $head is already green; nothing to do"; exit 0 ;;
  skipped) echo "run $id at $head was skipped (the PR was a draft then); nothing to re-run"; exit 0 ;;
esac
if gh run rerun "$id"; then echo "re-running t-workflow run $id at $head (was $conclusion)"
else echo "could not re-run run $id; re-run it from the Actions tab, or push again"; exit 1; fi
