#!/usr/bin/env bash
# The stage gates. Prints what a skill needs to know, one `key: value` per line, with
# every blocking condition as a `BLOCKED: <reason>` line.
#   gate.sh work <id>   may implementation start? issue open and not a parent, blockers
#                       closed as completed, a plan where the scope is protected, branch
#                       and PR state, whether this is a fresh or a fix pass.
#   gate.sh ship <id>   may the PR merge? one open PR, record present and valid, title,
#                       plan and current cold review on a protected diff, blockers,
#                       mergeability, nothing unpushed, the review's pending human checks
#                       (unknown when the review has no such section — that blocks) and
#                       its open medium/low findings.
#   exit 0 = proceed; 1 = at least one BLOCKED line; 2 = could not evaluate.
set -uo pipefail
. "$(dirname "$0")/lib.sh"

stage="${1:-}"; id="${2:-}"
[ -n "$stage" ] && [ -n "$id" ] || { awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 2; }
blocked=0
block() { echo "BLOCKED: $*"; blocked=1; }
cd "$TW_ROOT" || die "not in a repository"

issue=$(gh issue view "$id" --json number,title,state,labels,body 2>/dev/null) || die "issue #$id not found"
title=$(printf '%s' "$issue" | jq -r .title)
body=$(printf '%s' "$issue" | jq -r .body | normalize)
state=$(printf '%s' "$issue" | jq -r .state)
labels=$(printf '%s' "$issue" | jq -r '[.labels[].name] | join(",")')
plans=$(printf '%s\n' "$body" | count_sections Plan)
echo "issue: #$id $title"
echo "state: $state"
[ "$state" = "OPEN" ] || block "issue #$id is $state"
case ",$labels," in *,initiative,*) block "#$id is a parent (initiative) issue — it has no branch or PR of its own; work one of its children" ;; esac
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
  # Scope tokens: backticked paths in the issue's Scope, or the plan's Allowed paths.
  scope=$( { printf '%s\n' "$body" | section "Scope"; printf '%s\n' "$body" | section Plan; } \
    | grep -oE '`[^`]+`' | tr -d '`' | grep -E '^[A-Za-z0-9_./*-]+$' | sort -u || true)
  if [ -n "$scope" ]; then
    if prot=$(printf '%s\n' "$scope" | "$TW_SCRIPTS/protected.sh"); then
      echo "protected: $(printf '%s' "$prot" | tr '\n' ' ')"
      [ "$plans" -eq 1 ] || block "scope touches a protected path and the issue has no '## Plan' — run /t-plan $id"
    else echo "protected: none in the declared scope"; fi
  else echo "protected: scope not declared in backticks; judged from the diff later"; fi

  cands=$( { git for-each-ref --format='%(refname:short)' "refs/heads/wip/$id-*"; \
             git for-each-ref --format='%(refname:short)' "refs/remotes/origin/wip/$id-*" | sed 's#^origin/##'; } | sort -u)
  n=$(printf '%s\n' "$cands" | grep -c . || true)
  if [ "$n" -eq 0 ]; then echo "branch: none — create wip/$id-$(slugify "$title") from origin/$trunk"
  elif [ "$n" -eq 1 ]; then
    where="local"; git show-ref -q --verify "refs/heads/$cands" || where="remote only"
    git show-ref -q --verify "refs/remotes/origin/$cands" && [ "$where" = local ] && where="local and remote"
    echo "branch: $cands ($where)"
    if git show-ref -q --verify "refs/heads/$cands" && git show-ref -q --verify "refs/remotes/origin/$trunk"; then
      behind=$(git rev-list --count "$cands..origin/$trunk"); echo "branch-behind-trunk: $behind"
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
  if ! pr=$(pr_for_task "$id"); then
    rc=$?
    [ "$rc" -eq 3 ] && block "more than one open PR for #$id: $(printf '%s' "$pr" | tr '\n' ' ')"
    [ "$rc" -eq 1 ] && { if m=$(pr_for_task "$id" merged); then block "PR #$m for #$id is already merged"; else block "no PR for #$id — /t-work $id has not opened one"; fi; }
    exit 1
  fi
  v=$(gh pr view "$pr" --json number,title,url,isDraft,mergeable,headRefOid,headRefName,baseRefName,files,reviews,commits,statusCheckRollup)
  head=$(printf '%s' "$v" | jq -r .headRefOid); branch=$(printf '%s' "$v" | jq -r .headRefName)
  files=$(printf '%s' "$v" | jq -r '.files[].path')
  echo "pr: #$pr $(printf '%s' "$v" | jq -r .url)"
  echo "pr-title: $(printf '%s' "$v" | jq -r .title)"
  echo "draft: $(printf '%s' "$v" | jq -r .isDraft)"
  echo "head: $head ($branch → $(printf '%s' "$v" | jq -r .baseRefName))"
  echo "files: $(printf '%s\n' "$files" | grep -c .)"
  printf '%s' "$v" | jq -r .title | grep -qE "^\[$id\] " || block "PR title must start with '[$id] '"
  [ "$(printf '%s' "$v" | jq -r .baseRefName)" = "$trunk" ] || block "PR base is not the trunk ($trunk)"

  rec=$(printf '%s\n' "$files" | grep -E "^docs/tasks/$id-[^/]+\.md$" | head -1)
  if [ -z "$rec" ]; then block "no record docs/tasks/$id-<slug>.md in the PR"; else
    git fetch -q origin "$branch" 2>/dev/null
    tmp=$(mktemp -d); mkdir -p "$tmp/docs/tasks"
    if git show "origin/$branch:$rec" > "$tmp/$rec" 2>/dev/null; then
      out=$("$TW_SCRIPTS/record.sh" check "$id" "$tmp/$rec") || block "record: $(printf '%s' "$out" | tr '\n' ';')"
      echo "record: $rec"
    else echo "record: $rec (could not read it at origin/$branch)"; fi
    rm -rf "$tmp"
  fi

  if prot=$(printf '%s\n' "$files" | "$TW_SCRIPTS/protected.sh"); then
    echo "protected: $(printf '%s' "$prot" | tr '\n' ' ')"
    [ "$plans" -eq 1 ] || block "protected diff and no '## Plan' on the issue — run /t-plan $id, then /t-review $id"
    required=yes
  else echo "protected: none"; required=no; fi

  rv=$(review_verdict "$(printf '%s' "$v" | jq -c .reviews)" "$(printf '%s' "$v" | jq -r '.commits[-1].committedDate')")
  printf '%s\n' "$rv" | sed 's/^/review-/'
  out=$(printf '%s\n' "$rv" | review_blocks "$required" "$id") || blocked=1
  [ -n "$out" ] && printf '%s\n' "$out"
  m=$(printf '%s' "$v" | jq -r .mergeable); echo "mergeable: $m"
  [ "$m" = CONFLICTING ] && block "the branch conflicts with $trunk — rebase through /t-work $id"
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
