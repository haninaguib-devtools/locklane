#!/usr/bin/env bash
# One-time repository settings: the trunk moves only by squash-merged PRs with the
# `t-workflow` check green; merged branches are deleted.
#   protect.sh [--remove <context>]... [--add <context>]...
# With existing branch protection, only the required-checks list changes: the given
# contexts are removed and added, `t-workflow` is always present, and every other
# rule (reviews, admin enforcement, restrictions) stays exactly as it was. With no
# protection at all (a confirmed 404), the minimal set is applied. Any other failure
# to read the protection stops the script without writing anything.
#   exit 0 = done, or not available on this plan (said); 1 = a write failed;
#   2 = the protection could not be read.
set -uo pipefail
. "$(dirname "$0")/lib.sh"
remove=(); add=()
while [ $# -gt 0 ]; do
  case "$1" in
    --remove) remove+=("$2"); shift 2 ;;
    --add) add+=("$2"); shift 2 ;;
    *) die "unknown option $1" ;;
  esac
done
trunk=$(trunk); nwo=$(repo_nwo)
echo "repository: $nwo  trunk: $trunk"
if gh api -X PATCH "repos/$nwo" -F delete_branch_on_merge=true -F allow_squash_merge=true \
     -F allow_merge_commit=false -F allow_rebase_merge=false >/dev/null; then
  echo "OK: squash merges only, merged branches deleted"
else echo "FAIL: could not change merge settings"; fi

# Read the existing protection; the HTTP status decides what happens next.
err=$(mktemp); trap 'rm -f "$err"' EXIT
existing=$(gh api "repos/$nwo/branches/$trunk/protection" 2>"$err"); rc=$?
status=$(grep -oE 'HTTP [0-9]{3}' "$err" | head -1 | awk '{print $2}')
if [ "$rc" -ne 0 ]; then
  case "$status" in
    403) echo "FAIL: branch protection is not available on this repository (a private repository needs a paid plan); the rule holds by convention"; exit 0 ;;
    404)
      if gh api -X PUT "repos/$nwo/branches/$trunk/protection" --input - >/dev/null <<JSON
{"required_status_checks":{"strict":false,"contexts":["t-workflow"]},
 "enforce_admins":false,"required_pull_request_reviews":null,"restrictions":null,
 "allow_force_pushes":false,"allow_deletions":false}
JSON
      then echo "OK: $trunk protected — PRs only, 't-workflow' check required, no force pushes"; exit 0
      else echo "FAIL: branch protection not applied"; exit 1; fi ;;
    *) echo "FAIL: could not read the branch protection (HTTP ${status:-unknown}); nothing was changed:"; sed 's/^/  /' "$err"; exit 2 ;;
  esac
fi

before=$(printf '%s' "$existing" | jq -r '.required_status_checks.contexts[]?')
strict=$(printf '%s' "$existing" | jq -r '.required_status_checks.strict // false')
echo "required checks before: $(printf '%s' "$before" | tr '\n' ' ')"
new=$( { printf '%s\n' "$before"; printf '%s\n' t-workflow ${add[@]+"${add[@]}"}; } | grep . | awk '!seen[$0]++' )
for r in ${remove[@]+"${remove[@]}"}; do new=$(printf '%s\n' "$new" | grep -vxF -- "$r" || true); done
body=$(printf '%s\n' "$new" | grep . | jq -R . | jq -sc --argjson strict "$strict" '{strict: $strict, contexts: .}')
if printf '%s' "$body" | gh api -X PATCH "repos/$nwo/branches/$trunk/protection/required_status_checks" --input - >/dev/null 2>"$err"; then
  echo "OK: required checks now: $(printf '%s' "$new" | tr '\n' ' ')— every other protection rule left as it was"
elif printf '%s' "$existing" | jq -e '.required_status_checks == null' >/dev/null; then
  # Protection exists but has no required-checks rule yet (a reviews-only rule): the
  # sub-resource cannot be patched until it is enabled, so re-send the protection with
  # every existing rule carried over and the required checks added.
  full=$(printf '%s' "$existing" | jq -c --argjson rsc "$body" '
    def people: {users: [.users[]?.login], teams: [.teams[]?.slug], apps: [.apps[]?.slug]};
    {
      required_status_checks: $rsc,
      enforce_admins: (.enforce_admins.enabled // false),
      required_pull_request_reviews: (if .required_pull_request_reviews then (.required_pull_request_reviews | {
          dismiss_stale_reviews: (.dismiss_stale_reviews // false),
          require_code_owner_reviews: (.require_code_owner_reviews // false),
          required_approving_review_count: (.required_approving_review_count // 0),
          require_last_push_approval: (.require_last_push_approval // false)
        } + (if .dismissal_restrictions then {dismissal_restrictions: (.dismissal_restrictions | people)} else {} end)
          + (if .bypass_pull_request_allowances then {bypass_pull_request_allowances: (.bypass_pull_request_allowances | people)} else {} end))
        else null end),
      restrictions: (if .restrictions then (.restrictions | people) else null end),
      allow_force_pushes: (.allow_force_pushes.enabled // false),
      allow_deletions: (.allow_deletions.enabled // false),
      required_linear_history: (.required_linear_history.enabled // false),
      required_conversation_resolution: (.required_conversation_resolution.enabled // false),
      block_creations: (.block_creations.enabled // false),
      lock_branch: (.lock_branch.enabled // false),
      allow_fork_syncing: (.allow_fork_syncing.enabled // false)
    }')
  if printf '%s' "$full" | gh api -X PUT "repos/$nwo/branches/$trunk/protection" --input - >/dev/null; then
    echo "OK: required checks enabled: $(printf '%s' "$new" | tr '\n' ' ')— the existing rules carried over"
  else echo "FAIL: could not enable required checks on the existing protection"; exit 1; fi
else echo "FAIL: could not update the required checks:"; sed 's/^/  /' "$err"; exit 1; fi
