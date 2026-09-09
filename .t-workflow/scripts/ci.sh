#!/usr/bin/env bash
# Everything t-workflow's CI checks on a pull request, in one job
# (.github/workflows/t-workflow.yml): the workflow gates only — record, title, plan,
# review, blockers. The project's build is not run here; it belongs to the project's own
# CI, and the ship gate watches every check on the PR. Environment: BASE_REF, HEAD_REF,
# PR_NUMBER, PR_TITLE, GH_TOKEN. Every check runs even after one fails; exit 1 when any failed.
set -uo pipefail
. "$(dirname "$0")/lib.sh"
cd "$TW_ROOT" || die "not in a repository"
: "${BASE_REF:?}" "${HEAD_REF:?}" "${PR_NUMBER:?}" "${PR_TITLE:?}"
rc=0
ok()   { echo "OK: $*"; }
fail() { echo "FAIL: $*"; rc=1; }

git fetch -q origin "$BASE_REF" 2>/dev/null || true
changed=$(git -c core.quotePath=false diff --name-only "origin/$BASE_REF"...HEAD)
[ -n "$changed" ] || fail "this PR changes no files"

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
  if [ -z "$id" ]; then
    fail "branch '$HEAD_REF' is not wip/<id>-<slug>; every PR is a task (or add the branch pattern to config: exempt)"
  else
    # Record
    rec=$(printf '%s\n' "$changed" | grep -E "^docs/tasks/$id-[^/]+\.md$" | head -1)
    if [ -z "$rec" ]; then fail "no record docs/tasks/$id-<slug>.md in this PR"
    else out=$("$TW_SCRIPTS/record.sh" check "$id" "$rec") && ok "record $rec" || fail "$(printf '%s' "$out" | tr '\n' ';')"; fi
    # Title
    printf '%s' "$PR_TITLE" | grep -qE "^\[$id\] ." && ok "title starts with [$id]" || fail "PR title must start with '[$id] '"
    # Issue: plan and blockers
    if raw=$(gh issue view "$id" --json body -q .body 2>/dev/null); then body=$(printf '%s\n' "$raw" | normalize); else fail "cannot read issue #$id"; body=""; fi
    plans=$(printf '%s\n' "$body" | count_sections Plan)
    if prot=$(printf '%s\n' "$changed" | "$TW_SCRIPTS/protected.sh"); then
      echo "protected paths: $(printf '%s' "$prot" | tr '\n' ' ')"
      [ "$plans" -eq 1 ] && ok "protected diff has exactly one '## Plan'" || fail "protected diff needs exactly one '## Plan' on issue #$id (found $plans)"
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
