#!/usr/bin/env bash
# One call for what a cold reader needs.
#   snapshot.sh review <id>   JSON: {issue, plan, children, pr:{number,url,title,headRefOid,headRefName,baseRefName,files,reviews,head_time,checks_run}, diff, local:{head,clean}}
#                             children: for an initiative, [{number,title,state,stateReason,plan,record}] — its plans and records are theirs
#   snapshot.sh status        text: parents and their children, open tasks with blockers, branch, PR, checks, review; warnings
set -uo pipefail
. "$(dirname "$0")/lib.sh"
cd "$TW_ROOT" || die "not in a repository"

case "${1:-}" in
review)
  id="${2:-}"; [ -n "$id" ] || die "usage: snapshot.sh review <id>"
  pr=$(pr_for_task "$id" all) || { [ $? -eq 3 ] && die "more than one PR for #$id: $(printf '%s' "$pr" | tr '\n' ' ')"; die "no PR for #$id"; }
  issue=$(gh issue view "$id" --json number,title,state,labels,body,parent --jq '. + {labels: [.labels[].name], parent: (.parent.number // null)}')
  plan=$(printf '%s' "$issue" | jq -r .body | normalize | section Plan)
  prv=$(gh pr view "$pr" --json number,url,title,body,isDraft,headRefOid,headRefName,baseRefName,files,reviews,commits \
        --jq '{number,url,title,isDraft,headRefOid,headRefName,baseRefName,files: [.files[].path],reviews, head_time: .commits[-1].committedDate, checks_run: (.body | capture("## Checks run\n(?<c>(.|\n)*?)(\n## |$)").c? // "")}')
  diff=$(gh pr diff "$pr")
  children='[]'
  if printf '%s' "$issue" | jq -e '.labels | index("initiative")' >/dev/null; then
    children=$(children_json "$id" | jq -c --argjson files "$(printf '%s' "$prv" | jq -c .files)" \
      '[.[] | . + {record: (. as $c | $files | map(select(test("^docs/tasks/\($c.number)-[^/]+\\.md$"))) | first)}]')
    while IFS= read -r c; do
      [ -n "$c" ] || continue
      cplan=$(gh issue view "$c" --json body -q .body | normalize | section Plan)
      children=$(printf '%s' "$children" | jq -c --argjson n "$c" --arg p "$cplan" 'map(if .number == $n then . + {plan: $p} else . end)')
    done < <(printf '%s' "$children" | jq -r '.[].number')
  fi
  clean=true; [ -z "$(git status --porcelain | grep -v '^??')" ] || clean=false
  jq -n --argjson issue "$issue" --arg plan "$plan" --argjson children "$children" --argjson pr "$prv" --arg diff "$diff" \
        --arg head "$(git rev-parse HEAD)" --arg branch "$(git branch --show-current)" --argjson clean "$clean" \
        '{issue: $issue, plan: $plan, children: $children, pr: $pr, diff: $diff, local: {head: $head, branch: $branch, clean: $clean}}' ;;

status)
  issues=$(gh issue list --state open --limit 200 --json number,title,labels,blockedBy,parent \
    --jq '[.[] | {number,title,labels: [.labels[].name], blockedBy: [.blockedBy.nodes[]?.number], parent: (.parent.number // null)}]')
  prs=$(gh pr list --state open --limit 200 --json number,title,headRefName,isDraft,reviews,statusCheckRollup,updatedAt \
    --jq '[.[] | {number,headRefName,isDraft,updatedAt,
           review: ([.reviews[] | select(.body | test("readiness: *(ready|not-ready)"))] | sort_by(.submittedAt) | last | (.body // "") | capture("readiness: *(?<v>ready|not-ready)").v? // "none"),
           checks: ([.statusCheckRollup[]? | (.conclusion // .state // "pending")] | if length == 0 then "none" elif all(. == "SUCCESS") then "green" elif any(. == "FAILURE" or . == "ERROR") then "red" else "pending" end)}]')
  git fetch -q --prune origin 2>/dev/null || true
  branches=$(git for-each-ref --format='%(refname:short)' 'refs/remotes/origin/wip/*' | sed 's#^origin/##')
  echo "## Parents"
  printf '%s' "$issues" | jq -r --argjson prs "$prs" --arg br "$branches" '
    ($br | split("\n")) as $branches |
    .[] | select(.labels | index("initiative")) |
    ("wip/\(.number)-integration") as $ib |
    ($prs | map(select(.headRefName == $ib)) | first) as $pr |
    "- #\(.number) \(.title)" +
    (if ($branches | index($ib)) then " · integration branch" else "" end) +
    (if $pr then " · PR #\($pr.number) \(if $pr.isDraft then "draft" else "ready" end), checks \($pr.checks), review \($pr.review)" else "" end)' | grep . || echo "- none"
  echo; echo "## Open tasks"
  printf '%s' "$issues" | jq -r --argjson prs "$prs" --arg br "$branches" '
    ($br | split("\n")) as $branches |
    .[] | select(.labels | index("initiative") | not) |
    . as $i |
    ($prs | map(select(.headRefName | test("^wip/\($i.number)-"))) | first) as $pr |
    ($branches | map(select(test("^wip/\($i.number)-"))) | first) as $b |
    "- #\(.number) \(.title)" +
    (if .parent then " · part of #\(.parent)" else "" end) +
    (if (.blockedBy | length) > 0 then " · blocked by \(.blockedBy | map("#\(.)") | join(", "))" else "" end) +
    (if $b then " · branch" else "" end) +
    (if $pr then " · PR #\($pr.number) \(if $pr.isDraft then "draft" else "ready" end), checks \($pr.checks), review \($pr.review)" else "" end)' | grep . || echo "- none"
  echo; echo "## Warnings"
  w=0
  while IFS= read -r b; do
    [ -z "$b" ] && continue
    n=$(printf '%s' "$b" | sed -n -E 's#^wip/([0-9]+)-.*#\1#p')
    printf '%s' "$issues" | jq -e --argjson n "$n" 'any(.[]; .number == $n)' >/dev/null || { echo "- branch $b has no open issue"; w=1; }
  done <<< "$branches"
  printf '%s' "$prs" | jq -r '.[] | select(.headRefName | test("^wip/[0-9]+-") | not) | "- PR #\(.number) is not on a wip/<id>-<slug> branch (\(.headRefName))"' | grep . && w=1
  [ "$w" -eq 0 ] && echo "- none" ;;
*) sed -n '2,4p' "$0" | sed 's/^# //'; exit 2 ;;
esac
