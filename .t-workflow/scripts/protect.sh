#!/usr/bin/env bash
# One-time repository settings: the trunk moves only by squash-merged PRs with the
# `t-workflow` check green; merged branches are deleted. A second rule covers every
# initiative's integration branch, `wip/*-integration`, the same way (PRs only, the
# check required, no force pushes) but allows deletion, so the branch can go once the
# initiative's PR merges.
#   protect.sh [--remove <context>]... [--add <context>]...
# With existing protection, only the required-checks list changes: the given contexts
# are removed and added, `t-workflow` is always present, and every other rule
# (reviews, admin enforcement, restrictions) stays exactly as it was. With no
# protection at all (a confirmed 404, or no rule for the pattern), the minimal set is
# applied. Any other failure to read the protection stops the script without writing.
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
err=$(mktemp); trap 'rm -f "$err"' EXIT

# merge_contexts <existing contexts, one per line>: the list with t-workflow and every
# --add present, every --remove gone, in order, once each.
merge_contexts() {
  local new r
  new=$( { cat; printf '%s\n' t-workflow ${add[@]+"${add[@]}"}; } | grep . | awk '!seen[$0]++' )
  for r in ${remove[@]+"${remove[@]}"}; do new=$(printf '%s\n' "$new" | grep -vxF -- "$r" || true); done
  printf '%s\n' "$new" | grep .
}

# --- the trunk: a classic per-branch rule through the REST API --------------------
protect_trunk() {
  local existing rc status before strict new body full
  existing=$(gh api "repos/$nwo/branches/$trunk/protection" 2>"$err"); rc=$?
  status=$(grep -oE 'HTTP [0-9]{3}' "$err" | head -1 | awk '{print $2}')
  if [ "$rc" -ne 0 ]; then
    case "$status" in
      403) echo "FAIL: branch protection is not available on this repository (a private repository needs a paid plan); the rule holds by convention"; return 3 ;;
      404)
        if gh api -X PUT "repos/$nwo/branches/$trunk/protection" --input - >/dev/null <<JSON
{"required_status_checks":{"strict":false,"contexts":["t-workflow"]},
 "enforce_admins":false,"required_pull_request_reviews":null,"restrictions":null,
 "allow_force_pushes":false,"allow_deletions":false}
JSON
        then echo "OK: $trunk protected — PRs only, 't-workflow' check required, no force pushes"; return 0
        else echo "FAIL: branch protection not applied"; return 1; fi ;;
      *) echo "FAIL: could not read the branch protection (HTTP ${status:-unknown}); nothing was changed:"; sed 's/^/  /' "$err"; return 2 ;;
    esac
  fi
  before=$(printf '%s' "$existing" | jq -r '.required_status_checks.contexts[]?')
  strict=$(printf '%s' "$existing" | jq -r '.required_status_checks.strict // false')
  echo "required checks before: $(printf '%s' "$before" | tr '\n' ' ')"
  new=$(printf '%s\n' "$before" | merge_contexts)
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
    else echo "FAIL: could not enable required checks on the existing protection"; return 1; fi
  else echo "FAIL: could not update the required checks:"; sed 's/^/  /' "$err"; return 1; fi
  return 0
}

# --- integration branches: a pattern rule, which only the GraphQL API can create ----
protect_integration() {
  local pattern="wip/*-integration" rules rule rid before new out body
  rules=$(gh api graphql -F o="${nwo%/*}" -F n="${nwo#*/}" -f query='query($o:String!,$n:String!){repository(owner:$o,name:$n){id branchProtectionRules(first:100){nodes{id pattern requiredStatusCheckContexts}}}}' 2>"$err") \
    || { echo "FAIL: could not read the branch protection rules; nothing was changed:"; sed 's/^/  /' "$err"; return 2; }
  rid=$(printf '%s' "$rules" | jq -r .data.repository.id)
  rule=$(printf '%s' "$rules" | jq -c --arg p "$pattern" '.data.repository.branchProtectionRules.nodes[] | select(.pattern == $p)' | head -1)
  if [ -z "$rule" ]; then
    out=$(gh api graphql -F r="$rid" -F p="$pattern" -f query='mutation($r:ID!,$p:String!){createBranchProtectionRule(input:{repositoryId:$r,pattern:$p,requiresStatusChecks:true,requiredStatusCheckContexts:["t-workflow"],allowsForcePushes:false,allowsDeletions:true}){branchProtectionRule{id}}}' 2>&1) \
      && { echo "OK: $pattern protected — PRs only, 't-workflow' check required, no force pushes, deletions allowed"; return 0; }
    case "$out" in
      *"pgrade to GitHub"*|*"not available"*) echo "FAIL: branch protection is not available on this repository (a private repository needs a paid plan); the $pattern rule holds by convention"; return 3 ;;
      *) echo "FAIL: the $pattern rule was not created:"; printf '%s\n' "$out" | sed 's/^/  /'; return 1 ;;
    esac
  fi
  before=$(printf '%s' "$rule" | jq -r '.requiredStatusCheckContexts[]?')
  echo "$pattern required checks before: $(printf '%s' "$before" | tr '\n' ' ')"
  new=$(printf '%s\n' "$before" | merge_contexts)
  # The contexts go as a JSON list in the request body, so an empty list is still a
  # list (a variable declared non-null must be present, and -F cannot send [] ).
  body=$(printf '%s\n' "$new" | grep . | jq -R . | jq -sc --arg id "$(printf '%s' "$rule" | jq -r .id)" \
    '{query: "mutation($id:ID!,$ctx:[String!]!){updateBranchProtectionRule(input:{branchProtectionRuleId:$id,requiresStatusChecks:true,requiredStatusCheckContexts:$ctx}){branchProtectionRule{id}}}", variables: {id: $id, ctx: .}}')
  if printf '%s' "$body" | gh api graphql --input - >/dev/null 2>"$err"; then
    echo "OK: $pattern required checks now: $(printf '%s' "$new" | tr '\n' ' ')— every other setting of that rule left as it was"
  else echo "FAIL: could not update the $pattern rule:"; sed 's/^/  /' "$err"; return 1; fi
  return 0
}

protect_trunk; rc=$?
[ "$rc" -eq 3 ] && exit 0            # no plan for protection: the pattern rule needs the same plan
[ "$rc" -ne 0 ] && exit "$rc"
protect_integration; rc=$?
[ "$rc" -eq 3 ] && exit 0
exit "$rc"
