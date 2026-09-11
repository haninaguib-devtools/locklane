#!/usr/bin/env bash
# Everything t-workflow's CI checks on a pull request, in one job
# (.github/workflows/t-workflow.yml): the workflow gates only — record, title, plan,
# review, blockers. A child of an initiative (base: its integration branch) is judged
# like any task; an initiative's own PR (head: wip/<id>-integration) by its children's
# records and the combined diff's review. The project's build is not run here; it
# belongs to the project's own CI, and the ship gate watches every check on the PR.
# Environment: BASE_REF, HEAD_REF, PR_NUMBER, PR_TITLE, GH_TOKEN, PR_REF (optional).
# Every check runs even after one fails; exit 1 when any failed.
set -uo pipefail
. "$(dirname "$0")/lib.sh"
cd "$TW_ROOT" || die "not in a repository"
: "${BASE_REF:?}" "${HEAD_REF:?}" "${PR_NUMBER:?}" "${PR_TITLE:?}"
# PR_REF names a fetched commit that holds the PR's own content — read from with `git
# show`/`git diff`, never checked out or executed. The workflow keeps the actual
# checkout on the base branch and sets PR_REF to a ref it fetched separately, so the
# PR's code is only ever looked at, never run. The default, HEAD, is what a local run
# or a direct checkout of the PR already is.
PR_REF="${PR_REF:-HEAD}"
rc=0
ok()   { echo "OK: $*"; }
fail() { echo "FAIL: $*"; rc=1; }

git fetch -q origin "$BASE_REF" 2>/dev/null || true
diff_range="origin/$BASE_REF...$PR_REF"
changed=$(git -c core.quotePath=false diff --name-only "$diff_range")
[ -n "$changed" ] || fail "this PR changes no files"

# Policy (exempt, protected, docs) is read from the base branch, so a PR cannot
# judge itself by its own values. check stays the PR's own: a PR that changes the
# build is tested with its own command, and that change is visible in the diff.
# The merged copy is exported for the gate's children (protected.sh re-reads the
# config through lib.sh), so they judge by the same values.
pr_cfg=$(mktemp); base_cfg=$(mktemp); merged_cfg=$(mktemp)
trap 'rm -f "$pr_cfg" "$base_cfg" "$merged_cfg"' EXIT
check_pr="$check"
if [ "$PR_REF" != HEAD ]; then
  if git show "$PR_REF:.t-workflow/config" > "$pr_cfg" 2>/dev/null; then load_config "$pr_cfg"; else load_config ""; fi
  check_pr="$check"
fi
if git cat-file -e "origin/$BASE_REF:.t-workflow/AGENTS.md" 2>/dev/null; then
  if git show "origin/$BASE_REF:.t-workflow/config" > "$base_cfg" 2>/dev/null; then
    load_config "$base_cfg"
    check="$check_pr"
    # shellcheck disable=SC2154 # protected, docs, exempt, reviewer_model: set by load_config (lib.sh), sourced dynamically
    printf 'check="%s"\nprotected="%s"\ndocs="%s"\nexempt="%s"\nreviewer_model="%s"\n' \
      "$check" "$protected" "$docs" "$exempt" "$reviewer_model" > "$merged_cfg"
    export TW_CONFIG_FILE="$merged_cfg"
    echo "policy: exempt/protected/docs from origin/$BASE_REF; check from the PR"
  else
    echo "note: origin/$BASE_REF has no .t-workflow/config; judging by the PR's values"
  fi
fi

exempt_branch=no
# shellcheck disable=SC2086
match_any "$HEAD_REF" $exempt 2>/dev/null && exempt_branch=yes
[ -z "$exempt" ] && exempt_branch=no

if ! git cat-file -e "origin/$BASE_REF:.t-workflow/AGENTS.md" 2>/dev/null; then
  ok "adoption PR: $BASE_REF has no t-workflow yet, so the task gates are not in force until this merges"
elif [ "$exempt_branch" = yes ]; then
  ok "branch $HEAD_REF is exempt from the task gates (config: exempt)"
else
  id=$(printf '%s' "$HEAD_REF" | sed -n -E 's#^wip/([0-9]+)-.+#\1#p')
  # The issue: its body (the plan), and whether it is a parent — the label, never the
  # branch's slug, so a task that happens to be called "integration" is still a task.
  parent_pr=no; body=""; istate=""; ireason=""
  if [ -n "$id" ]; then
    if iv=$(gh issue view "$id" --json body,state,stateReason,labels 2>/dev/null); then
      body=$(printf '%s' "$iv" | jq -r .body | normalize)
      istate=$(printf '%s' "$iv" | jq -r .state); ireason=$(printf '%s' "$iv" | jq -r '.stateReason // ""')
      printf '%s' "$iv" | jq -e '.labels | any(.name == "initiative")' >/dev/null && parent_pr=yes
    else issue_unread=yes; fi
  fi
  # check_record <id> <path> [state reason]: the record read through PR_REF (never
  # assumed on disk). A record the PR deletes is accepted only when the issue it
  # belongs to is closed as not planned — the PR's own issue, or on a parent's PR the
  # child's, whose state the caller already holds: that is /t-cancel's revert of a
  # child already on an integration branch.
  check_record() {
    local rid="$1" rec="$2" rstate="${3:-$istate}" rreason="${4:-$ireason}" out rec_check
    if ! git cat-file -e "$PR_REF:$rec" 2>/dev/null; then
      if [ "$rstate" = CLOSED ] && [ "$rreason" = NOT_PLANNED ]; then ok "record $rec removed by the revert of cancelled #$rid"
      else fail "record $rec is deleted in this PR, and #$rid is not cancelled"; fi
      return
    fi
    if [ "$PR_REF" = HEAD ]; then
      out=$("$TW_SCRIPTS/record.sh" check "$rid" "$rec") && ok "record $rec" || fail "$(printf '%s' "$out" | tr '\n' ';')"
    else
      rec_check="$(mktemp -d)/$rec"; mkdir -p "$(dirname "$rec_check")"
      if git show "$PR_REF:$rec" > "$rec_check" 2>/dev/null; then
        out=$("$TW_SCRIPTS/record.sh" check "$rid" "$rec_check") && ok "record $rec" || fail "$(printf '%s' "$out" | tr '\n' ';')"
      else fail "could not read $rec from the PR ($PR_REF)"; fi
    fi
  }
  if [ -z "$id" ]; then
    fail "branch '$HEAD_REF' is not wip/<id>-<slug>; every PR is a task (or add the branch pattern to config: exempt)"
  else
    if [ "$parent_pr" = yes ]; then
      # An initiative's PR, integration branch to the trunk: its records are the
      # children's — every completed child's present and valid, no cancelled child's
      # (its revert has landed), no child still open. No plan of its own.
      if kids=$(children_json "$id" 2>/dev/null); then
        [ "$(printf '%s' "$kids" | jq length)" -gt 0 ] || fail "#$id has no children"
        while IFS=$'\t' read -r cnum cstate creason ctitle; do
          [ -n "$cnum" ] || continue
          rec=$(printf '%s\n' "$changed" | grep -E "^docs/tasks/$cnum-[^/]+\.md$" | head -1)
          if [ "$cstate" = OPEN ]; then fail "child #$cnum ($ctitle) is still open"
          elif [ "$creason" = COMPLETED ]; then
            # A child that merged into the trunk itself, under a release that had no
            # integration branch, has its record there already, not in this diff.
            if [ -n "$rec" ]; then check_record "$cnum" "$rec" "$cstate" "$creason"
            elif rec=$(git ls-tree --name-only "origin/$BASE_REF" docs/tasks/ 2>/dev/null | grep -E "^docs/tasks/$cnum-[^/]+\.md$" | head -1) && [ -n "$rec" ]; then
              ok "record $rec of completed child #$cnum is already on $BASE_REF (merged there before the integration branch existed)"
            else fail "completed child #$cnum has no record docs/tasks/$cnum-<slug>.md in this PR or on $BASE_REF"; fi
          elif [ -n "$rec" ] && git cat-file -e "$PR_REF:$rec" 2>/dev/null; then fail "cancelled child #$cnum is still on $HEAD_REF (its record is in the diff)"
          elif [ -n "$rec" ]; then check_record "$cnum" "$rec" "$cstate" "$creason"
          else ok "cancelled child #$cnum is not in the diff"; fi
        done < <(printf '%s' "$kids" | jq -r '.[] | [.number, .state, (.stateReason // "open"), .title] | @tsv')
      else fail "cannot read the children of #$id"; fi
    else
      rec=$(printf '%s\n' "$changed" | grep -E "^docs/tasks/$id-[^/]+\.md$" | head -1)
      if [ -z "$rec" ]; then fail "no record docs/tasks/$id-<slug>.md in this PR"; else check_record "$id" "$rec"; fi
    fi
    # Title
    printf '%s' "$PR_TITLE" | grep -qE "^\[$id\] ." && ok "title starts with [$id]" || fail "PR title must start with '[$id] '"
    # Issue: plan and blockers
    [ "${issue_unread:-}" = yes ] && fail "cannot read issue #$id"
    plans=$(printf '%s\n' "$body" | count_sections Plan)
    if prot=$(printf '%s\n' "$changed" | "$TW_SCRIPTS/protected.sh"); then
      echo "protected paths: $(printf '%s' "$prot" | tr '\n' ' ')"
      if [ "$parent_pr" = yes ]; then ok "a parent's plans are its children's; each child was gated on its own"
      else [ "$plans" -eq 1 ] && ok "protected diff has exactly one '## Plan'" || fail "protected diff needs exactly one '## Plan' on issue #$id (found $plans)"; fi
      reviews=$(gh pr view "$PR_NUMBER" --json reviews,commits 2>/dev/null) || reviews='{"reviews":[],"commits":[]}'
      rv=$(review_verdict "$(printf '%s' "$reviews" | jq -c .reviews)" "$(printf '%s' "$reviews" | jq -r '.commits[-1].committedDate // ""')")
      verdict=$(printf '%s\n' "$rv" | sed -n 's/^verdict: //p'); fresh=$(printf '%s\n' "$rv" | sed -n 's/^fresh: //p'); iso=$(printf '%s\n' "$rv" | sed -n 's/^isolation: //p')
      if [ "$verdict" = ready ] && [ "$fresh" = yes ]; then
        case "$iso" in *"same session"*) fail "cold review isolation is 'same session'" ;; *) ok "current cold review: ready ($iso)" ;; esac
      else fail "protected diff needs a cold review with 'readiness: ready' newer than the head commit (found: $verdict, fresh: $fresh)"; fi
    else
      [ "$plans" -le 1 ] && ok "not a protected diff" || fail "issue #$id carries $plans '## Plan' sections"
    fi
    if ob=$(open_blockers "$id"); then ok "no open blockers"; else fail "blockers not closed as completed: $(printf '%s' "$ob" | tr '\n' ';')"; fi
  fi
fi

echo "check 1 (the project's build) is not run here; the project's own CI runs it"
exit "$rc"
