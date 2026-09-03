#!/usr/bin/env bash
# Settings as code (workflow §10, mitigation 2): recreate the repo's GitHub configuration in one
# command. Run after creating the GitHub repository, from a checkout with `gh` logged in.
# Idempotent: safe to re-run.
set -euo pipefail

repo=$(gh repo view --json nameWithOwner -q .nameWithOwner)
echo "Bootstrapping $repo"

# The trunk's name, read from GitHub itself rather than `.t-workflow/scripts/trunk-ref.sh`'s
# `origin/HEAD` git ref: this script runs at genesis, right after the first push, when a
# freshly-`git init`'d checkout has never had `origin/HEAD` set (that symref is set by
# `git clone`/`git remote set-head`, not by `git push`) — `git symbolic-ref` would fall
# through to trunk-ref.sh's own `main` fallback even on a repo whose actual default
# branch is something else, reintroducing the exact silent-wrong-default bug this task
# fixes. GitHub's own record of the default branch has no such gap.
trunk=$(gh api "repos/$repo" --jq .default_branch)
echo "Trunk branch: $trunk"

# --- Labels -----------------------------------------------------------------
label() { gh label create "$1" --color "$2" --description "$3" --force; }
label "initiative" "8250DF" "Tracking issue: intent + ordered child tasks"
label "cancelled"  "6E7781" "Task cancelled via /t-cancel — see the close comment for the reason"

# Classification labels (docs/adapters/TRACKER.md): /t-open tags every task issue with
# one of these. Colors/descriptions match GitHub's own repo-creation defaults so this is
# idempotent whether or not those defaults already exist.
label "bug"           "d73a4a" "Something isn't working"
label "enhancement"   "a2eeef" "New feature or request"
label "documentation" "0075ca" "Improvements or additions to documentation"
label "question"      "d876e3" "Further information is requested"

# --- Merge mechanics: squash only -------------------------------------------
gh api "repos/$repo" -X PATCH \
  -F allow_squash_merge=true -F allow_merge_commit=false -F allow_rebase_merge=false \
  -F delete_branch_on_merge=true >/dev/null
echo "Merge mechanics set: squash-only, delete branch on merge."

# --- Branch protection on the trunk ------------------------------------------
# PRs only; no force pushes; no deletions.
#
# REQUIRED APPROVALS ARE 0 ON PURPOSE — do not raise this to 1 while one person works
# the repo. GitHub forbids approving your own pull request, so "1 approval" plus
# "enforce_admins" makes every PR unmergeable by its only author: /t-ship would pass its
# gate and then fail at the merge with no way through. The human read at /t-ship's gate
# is the approval in the solo phase (ADR-001 D1). Raise it to 1 — and only then — when a
# second maintainer exists and the approval policy (workflow §13 Q9) is decided.
#
# `enforce_admins` stays true: it is what stops a direct push to the trunk, which is the
# invariant the whole workflow rests on.
#
# The job from .github/workflows/ci.yml becomes a required check once CI has run at
# least once on the default branch; a context GitHub has never seen can be rejected
# here, so this script sets it only after seeing `checks` land on the trunk. (#94/#113
# folded what were six separate jobs — consistency, record, plan-gate, title-gate,
# blockers, plumbing-test — into sequential steps of that one `checks` job, so there is
# only one ci.yml context to require now. `cold-review` stays a separate required
# context: it is a different job, in review-gate.yml, gated on the pull_request_review
# trigger that job needs and this one doesn't.)
#
# NOTE: on a PRIVATE repo, branch protection (and rulesets, and CODEOWNERS enforcement)
# requires a paid plan — GitHub Pro (personal) or Team (org). On free plans it works
# only on PUBLIC repos. The call below degrades to a warning instead of failing.
# When the context can't be confirmed on the trunk, the fallback must preserve whatever
# is currently enforced — this PUT *overwrites* the live setting, so falling back to
# null on an already-protected repo would silently disable required checks entirely
# (#113's own ship tripped exactly that, pre-merge). Read the live value first; fall
# back to it.
current=$(gh api "repos/$repo/branches/$trunk/protection/required_status_checks" \
  --jq '{strict: .strict, contexts: .contexts}' 2>/dev/null || echo null)
if gh api "repos/$repo/commits/$trunk/check-runs" -q '.check_runs[].name' 2>/dev/null \
   | grep -qx checks; then
  checks='{"strict": false, "contexts": ["checks", "cold-review"]}'
  echo "CI has run on $trunk: marking checks and cold-review required checks."
elif [ "$current" != null ]; then
  checks="$current"
  echo "CI has not run on $trunk yet: keeping the currently-required checks unchanged:"
  echo "  $current"
  echo "  Re-run this script after the first CI run to require checks + cold-review."
else
  checks='null'
  echo "CI has not run on $trunk yet and no checks are currently required: leaving unset."
  echo "  Re-run this script after the first CI run to make it required."
fi

protection_err=$(mktemp)
if gh api "repos/$repo/branches/$trunk/protection" -X PUT \
  -H "Accept: application/vnd.github+json" \
  --input - <<JSON >/dev/null 2>"$protection_err"
{
  "required_status_checks": $checks,
  "enforce_admins": true,
  "required_pull_request_reviews": { "required_approving_review_count": 0 },
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false
}
JSON
then
  echo "Branch protection set: PRs only, 0 required approvals (solo phase), no force pushes."
else
  # Print what actually failed. Blaming the plan for every failure hides bad token scopes,
  # a missing default branch, and typos in the check names.
  echo "WARNING: branch protection could not be set. The API said:"
  sed 's/^/    /' "$protection_err"
  echo "  Most often this is a PRIVATE repo on a free plan, where branch protection needs"
  echo "  GitHub Pro or Team. Other causes worth ruling out: the token lacks 'repo' or"
  echo "  'administration' scope, '$trunk' does not exist yet (push first), or a required"
  echo "  check name has never run on $trunk."
  echo "  Until it is set, PR-only $trunk runs on convention — the skills never commit to"
  echo "  $trunk directly, but nothing *enforces* it. Re-run this script once fixed."
fi
rm -f "$protection_err"

echo "Done. TODO when decided/available: CODEOWNERS owners, and the approval policy (workflow §13 Q9) — raising required approvals above 0 needs a second maintainer first."
