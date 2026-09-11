#!/usr/bin/env bash
# Re-run the t-workflow CI runs for a PR's head commit once a cold review exists: a
# run that fired on the push was red only because no review existed yet, and
# re-running it is what turns it green — nothing for a human to do. One run per
# commit and event; a PR that touches the workflow file has two.
#   rerun-ci.sh <pr>
#   exit 0 = re-run started, or nothing to do (said why); 1 = the re-run could not be
#   started; 2 = the PR or its runs could not be read.
set -uo pipefail
. "$(dirname "$0")/lib.sh"
pr="${1:-}"; [ -n "$pr" ] || { sed -n '2,8p' "$0" | sed 's/^# //'; exit 2; }
v=$(gh pr view "$pr" --json headRefOid,isDraft) || die "cannot read PR #$pr"
head=$(printf '%s' "$v" | jq -r .headRefOid)
# gh answers a flag-shaped argument with its help text and exit 0: no head, and an
# empty --commit would list every run in the repository.
[ -n "$head" ] && [ "$head" != null ] || die "PR #$pr has no head commit"
if [ "$(printf '%s' "$v" | jq -r .isDraft)" = true ]; then
  echo "PR #$pr is a draft: CI runs when /t-ship marks it ready, and will see the review then"; exit 0
fi
runs=$(gh run list --workflow t-workflow --commit "$head" --json databaseId,status,conclusion) || die "cannot list runs for $head"
[ "$(printf '%s' "$runs" | jq length)" -gt 0 ] || { echo "no t-workflow run at $head; CI has not been triggered for this commit"; exit 0; }
# A PR that touches the workflow file has two runs at one commit (both events fire);
# every red one is re-run, since one left red still blocks.
rc=0
while IFS=$'\t' read -r id status conclusion; do
  [ -n "$id" ] || continue
  if [ "$status" != completed ]; then echo "run $id at $head is still $status; if it ends red, run this again"; continue; fi
  case "$conclusion" in
    success) echo "run $id at $head is already green; nothing to do"; continue ;;
    skipped) echo "run $id at $head was skipped (the PR was a draft then); nothing to re-run"; continue ;;
  esac
  if gh run rerun "$id"; then echo "re-running t-workflow run $id at $head (was $conclusion)"
  else echo "could not re-run run $id; re-run it from the Actions tab, or push again"; rc=1; fi
done < <(printf '%s' "$runs" | jq -r '.[] | [.databaseId, .status, (.conclusion // "")] | @tsv')
exit "$rc"
