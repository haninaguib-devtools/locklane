#!/usr/bin/env bash
# The stage gates. Prints what a skill needs to know, one `key: value` per line, with
# every blocking condition as a `BLOCKED: <reason>` line.
#   gate.sh work <id>   may implementation start? issue open and not a parent, blockers
#                       closed as completed, a plan where the scope is protected, the
#                       base branch (the trunk, or an initiative's integration branch,
#                       created from the trunk when absent), branch and PR state,
#                       whether this is a fresh or a fix pass.
#   gate.sh ship <id>   may the PR merge? one open PR on the right base, record present
#                       and valid, title, plan and current cold review on a protected
#                       diff, blockers, mergeability, nothing unpushed, the review's
#                       pending human checks (unknown when the review has no such
#                       section — that blocks) and its open medium/low findings.
#                       A child of an initiative merges into the integration branch
#                       (`merge: automatic`); a parent's PR is the integration branch
#                       to the trunk (`merge: confirm`): every child closed, every
#                       completed child's record in the diff (or already on the trunk,
#                       from before the integration branch existed), no cancelled child's.
#   exit 0 = proceed; 1 = at least one BLOCKED line; 2 = could not evaluate.
set -uo pipefail
. "$(dirname "$0")/lib.sh"

stage="${1:-}"; id="${2:-}"
[ -n "$stage" ] && [ -n "$id" ] || { awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 2; }
blocked=0
block() { echo "BLOCKED: $*"; blocked=1; }
cd "$TW_ROOT" || die "not in a repository"

issue=$(gh issue view "$id" --json number,title,state,labels,body,parent 2>/dev/null) || die "issue #$id not found"
title=$(printf '%s' "$issue" | jq -r .title)
body=$(printf '%s' "$issue" | jq -r .body | normalize)
state=$(printf '%s' "$issue" | jq -r .state)
labels=$(printf '%s' "$issue" | jq -r '[.labels[].name] | join(",")')
parent=$(printf '%s' "$issue" | jq -r '.parent.number // empty')
plans=$(printf '%s\n' "$body" | count_sections Plan)
kind=task; case ",$labels," in *,initiative,*) kind=parent ;; esac
[ "$kind" = task ] && [ -n "$parent" ] && kind=child
echo "issue: #$id $title"
echo "state: $state"
echo "kind: $kind$([ "$kind" = child ] && echo " of #$parent")"
[ "$state" = "OPEN" ] || block "issue #$id is $state"
[ "$plans" -gt 1 ] && block "issue carries $plans '## Plan' sections; exactly one may exist"
echo "plan: $([ "$plans" -eq 1 ] && echo yes || echo none)"

if ob=$(open_blockers "$id"); then echo "blockers: none open"; else
  echo "blockers:"; printf '%s\n' "$ob" | sed 's/^/  /'
  block "a blocker is not closed as completed (a cancelled blocker was abandoned, not satisfied)"
fi

trunk=$(trunk); echo "trunk: $trunk"
git fetch -q --prune origin 2>/dev/null || echo "note: git fetch failed; remote state may be stale"

case "$stage" in
work)
  [ "$kind" = parent ] && block "#$id is a parent (initiative) issue — it has no branch of its own; work one of its children, then /t-ship $id merges the integration branch"
  # Scope tokens: backticked paths in the issue's Scope, or the plan's Allowed paths.
  scope=$( { printf '%s\n' "$body" | section "Scope"; printf '%s\n' "$body" | section Plan; } \
    | grep -oE '`[^`]+`' | tr -d '`' | grep -E '^[A-Za-z0-9_./*-]+$' | sort -u || true)
  if [ -n "$scope" ]; then
    if prot=$(printf '%s\n' "$scope" | "$TW_SCRIPTS/protected.sh"); then
      echo "protected: $(printf '%s' "$prot" | tr '\n' ' ')"
      [ "$plans" -eq 1 ] || block "scope touches a protected path and the issue has no '## Plan' — run /t-plan $id"
    else echo "protected: none in the declared scope"; fi
  else echo "protected: scope not declared in backticks; judged from the diff later"; fi

  # The base: the trunk, or for a child of an initiative its integration branch,
  # created on origin from the trunk the first time a child is worked. The child
  # relation is the issue's own parent field; ci.sh and the ship gate know the parent
  # by its `initiative` label, so the label has to be there before the branch is.
  base="$trunk"
  if [ "$kind" = child ]; then
    base=$(integration_branch "$parent")
    plabels=$(gh issue view "$parent" --json labels --jq '[.labels[].name] | join(",")' 2>/dev/null) || die "cannot read the parent issue #$parent"
    if ! printf '%s' "$plabels" | grep -qE '(^|,)initiative(,|$)'; then
      block "parent #$parent has no 'initiative' label, so its integration branch would never be judged as a parent's — run: .t-workflow/scripts/issue.sh ensure-label initiative && gh issue edit $parent --add-label initiative"
    elif ! git show-ref -q --verify "refs/remotes/origin/$base"; then
      if perr=$(git push -q origin "origin/$trunk:refs/heads/$base" 2>&1) && git fetch -q origin 2>/dev/null; then
        echo "base: $base (integration branch of #$parent, created from origin/$trunk)"
      else block "could not create $base from origin/$trunk on origin: $(printf '%s' "$perr" | grep -v '^$' | tail -2 | tr '\n' ' ')"; fi
    else echo "base: $base (integration branch of #$parent)"; fi
  else echo "base: $trunk"; fi

  cands=$( { git for-each-ref --format='%(refname:short)' "refs/heads/wip/$id-*"; \
             git for-each-ref --format='%(refname:short)' "refs/remotes/origin/wip/$id-*" | sed 's#^origin/##'; } | sort -u)
  n=$(printf '%s\n' "$cands" | grep -c . || true)
  if [ "$n" -eq 0 ]; then echo "branch: none — create wip/$id-$(slugify "$title") from origin/$base"
  elif [ "$n" -eq 1 ]; then
    where="local"; git show-ref -q --verify "refs/heads/$cands" || where="remote only"
    git show-ref -q --verify "refs/remotes/origin/$cands" && [ "$where" = local ] && where="local and remote"
    echo "branch: $cands ($where)"
    if git show-ref -q --verify "refs/heads/$cands" && git show-ref -q --verify "refs/remotes/origin/$base"; then
      behind=$(git rev-list --count "$cands..origin/$base"); echo "branch-behind-base: $behind"
    fi
    wt=$(git worktree list --porcelain | awk -v b="refs/heads/$cands" '$1=="worktree"{w=$2} $1=="branch"&&$2==b{print w}')
    [ -n "$wt" ] && [ "$wt" != "$TW_ROOT" ] && block "branch $cands is checked out in another worktree: $wt"
  else block "more than one branch for #$id — decide by hand: $(printf '%s' "$cands" | tr '\n' ' ')"; fi

  cur=$(git rev-parse --abbrev-ref HEAD 2>/dev/null); echo "current-branch: $cur"
  if [ "$cur" = "$trunk" ] && git show-ref -q --verify "refs/remotes/origin/$trunk"; then
    a=$(git rev-list --count "origin/$trunk..$trunk"); b=$(git rev-list --count "$trunk..origin/$trunk")
    if [ "$a" -gt 0 ]; then block "local $trunk is $a commit(s) ahead of origin — never commit on the trunk"; fi
    [ "$b" -gt 0 ] && echo "trunk-behind-origin: $b (fast-forward it first)"
  fi
  dirty=$(git status --porcelain | grep -v '^??' || true)
  if [ -n "$dirty" ]; then echo "dirty:"; printf '%s\n' "$dirty" | sed 's/^/  /'; echo "note: stop if these changes are not this task's; never stash or discard them"; else echo "dirty: no"; fi

  if pr=$(pr_for_task "$id"); then
    echo "pr: #$pr (open)"
    rv=$(gh pr view "$pr" --json reviews,commits --jq '{reviews, head: .commits[-1].committedDate}')
    review_verdict "$(printf '%s' "$rv" | jq -c .reviews)" "$(printf '%s' "$rv" | jq -r .head)" | sed 's/^/review-/'
    echo "mode: fix (a PR exists — address blocker/high findings only, same branch and PR)"
  else
    rc=$?; [ "$rc" -eq 3 ] && block "more than one open PR for #$id: $(printf '%s' "$pr" | tr '\n' ' ')"
    echo "pr: none"; echo "mode: normal"
  fi ;;

ship)
  case "$kind" in
    parent) want_base="$trunk"; want_head=$(integration_branch "$id"); echo "merge: confirm" ;;
    child)  want_base=$(integration_branch "$parent"); want_head=""; echo "merge: automatic (into $want_base; the human's gate is /t-ship $parent)" ;;
    *)      want_base="$trunk"; want_head=""; echo "merge: confirm" ;;
  esac
  pr=$(pr_for_task "$id"); rc=$?
  if [ "$rc" -ne 0 ]; then
    [ "$rc" -eq 3 ] && block "more than one open PR for #$id: $(printf '%s' "$pr" | tr '\n' ' ')"
    if [ "$rc" -eq 1 ]; then
      if m=$(pr_for_task "$id" merged); then block "PR #$m for #$id is already merged"
      elif [ "$kind" = parent ]; then
        if git show-ref -q --verify "refs/remotes/origin/$want_head"; then
          block "no PR for #$id — open the integration PR: gh pr create --draft --base $trunk --head $want_head --title \"[$id] $title\" --body-file <file>"
        else block "no integration branch $want_head on origin — no child of #$id has been worked"; fi
      else block "no PR for #$id — /t-work $id has not opened one"; fi
    fi
    exit 1
  fi
  v=$(gh pr view "$pr" --json number,title,url,isDraft,mergeable,headRefOid,headRefName,baseRefName,files,reviews,commits,statusCheckRollup)
  head=$(printf '%s' "$v" | jq -r .headRefOid); branch=$(printf '%s' "$v" | jq -r .headRefName); prbase=$(printf '%s' "$v" | jq -r .baseRefName)
  files=$(printf '%s' "$v" | jq -r '.files[].path')
  echo "pr: #$pr $(printf '%s' "$v" | jq -r .url)"
  echo "pr-title: $(printf '%s' "$v" | jq -r .title)"
  echo "draft: $(printf '%s' "$v" | jq -r .isDraft)"
  echo "head: $head ($branch → $prbase)"
  echo "files: $(printf '%s\n' "$files" | grep -c .)"
  printf '%s' "$v" | jq -r .title | grep -qE "^\[$id\] " || block "PR title must start with '[$id] '"
  [ "$prbase" = "$want_base" ] || block "PR base is $prbase; a $kind's PR merges into $want_base"
  [ -n "$want_head" ] && [ "$branch" != "$want_head" ] && block "PR head is $branch; a parent's PR is its integration branch $want_head"
  git fetch -q origin "$branch" 2>/dev/null

  # check_record <id> <path>: the record as it is at the PR head, through record.sh.
  check_record() {
    local rid="$1" rec="$2" tmp out
    tmp=$(mktemp -d); mkdir -p "$tmp/docs/tasks"
    if git show "origin/$branch:$rec" > "$tmp/$rec" 2>/dev/null; then
      out=$("$TW_SCRIPTS/record.sh" check "$rid" "$tmp/$rec") || block "record: $(printf '%s' "$out" | tr '\n' ';')"
      echo "record: $rec"
    else echo "record: $rec (could not read it at origin/$branch)"; fi
    rm -rf "$tmp"
  }
  if [ "$kind" = parent ]; then
    # A parent's records are its children's: every completed child's, none of a
    # cancelled child's (its revert has to have landed), and no child still open.
    kids=$(children_json "$id") || die "cannot read the children of #$id"
    [ "$(printf '%s' "$kids" | jq length)" -gt 0 ] || block "#$id has no children"
    echo "children:"
    while IFS=$'\t' read -r cnum cstate creason ctitle; do
      [ -n "$cnum" ] || continue
      rec=$(printf '%s\n' "$files" | grep -E "^docs/tasks/$cnum-[^/]+\.md$" | head -1)
      echo "  #$cnum $ctitle ($cstate/$creason)"
      if [ "$cstate" = OPEN ]; then block "child #$cnum is still open — finish it (/t-drive $cnum) or cancel it (/t-cancel $cnum)"
      elif [ "$creason" = COMPLETED ]; then
        # A child that merged into the trunk itself, under a release that had no
        # integration branch, has its record there already, not in this diff.
        if [ -n "$rec" ]; then check_record "$cnum" "$rec"
        elif rec=$(git ls-tree --name-only "origin/$trunk" docs/tasks/ 2>/dev/null | grep -E "^docs/tasks/$cnum-[^/]+\.md$" | head -1) && [ -n "$rec" ]; then
          echo "record: $rec (already on $trunk — merged there before the integration branch existed)"
        else block "completed child #$cnum has no record docs/tasks/$cnum-<slug>.md in the PR or on $trunk — it never merged into $branch"; fi
      elif [ -n "$rec" ]; then block "cancelled child #$cnum is still on $branch — /t-cancel $cnum reverts it there"; fi
    done < <(printf '%s' "$kids" | jq -r '.[] | [.number, .state, (.stateReason // "open"), .title] | @tsv')
  else
    rec=$(printf '%s\n' "$files" | grep -E "^docs/tasks/$id-[^/]+\.md$" | head -1)
    if [ -z "$rec" ]; then block "no record docs/tasks/$id-<slug>.md in the PR"; else check_record "$id" "$rec"; fi
  fi

  if prot=$(printf '%s\n' "$files" | "$TW_SCRIPTS/protected.sh"); then
    echo "protected: $(printf '%s' "$prot" | tr '\n' ' ')"
    # A parent's plans are its children's; each child was gated on its own.
    [ "$kind" = parent ] || [ "$plans" -eq 1 ] || block "protected diff and no '## Plan' on the issue — run /t-plan $id, then /t-review $id"
    required=yes
  else echo "protected: none"; required=no; fi

  rv=$(review_verdict "$(printf '%s' "$v" | jq -c .reviews)" "$(printf '%s' "$v" | jq -r '.commits[-1].committedDate')")
  printf '%s\n' "$rv" | sed 's/^/review-/'
  out=$(printf '%s\n' "$rv" | review_blocks "$required" "$id") || blocked=1
  [ -n "$out" ] && printf '%s\n' "$out"
  m=$(printf '%s' "$v" | jq -r .mergeable); echo "mergeable: $m"
  if [ "$m" = CONFLICTING ]; then
    if [ "$kind" = parent ]; then block "the integration branch conflicts with $trunk — it is PR-only, so merge origin/$trunk into it through a child task (/t-open, then /t-drive it)"
    else block "the branch conflicts with $want_base — rebase through /t-work $id"; fi
  fi
  cur=$(git rev-parse --abbrev-ref HEAD 2>/dev/null)
  if [ "$cur" = "$branch" ]; then
    local_head=$(git rev-parse HEAD)
    [ "$local_head" = "$head" ] || block "local $branch ($local_head) differs from the PR head — push first"
    [ -z "$(git status --porcelain | grep -v '^??')" ] || block "uncommitted changes on $branch"
  else echo "note: not on $branch; local-vs-PR head comparison skipped"; fi
  echo "checks: $(printf '%s' "$v" | jq -r '[.statusCheckRollup[]? | "\(.name // .context): \(.conclusion // .state // "pending")"] | join(", ") | if . == "" then "none reported yet (CI starts when the PR is marked ready)" else . end')" ;;
*) die "unknown stage '$stage'" ;;
esac
exit "$blocked"
