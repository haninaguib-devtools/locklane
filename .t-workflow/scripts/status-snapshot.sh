#!/usr/bin/env bash
# One-shot read phase for /t-status: replaces five separate sequential tracker/forge
# queries (initiatives, tasks, PRs, local git state, cancellations) with one script
# invocation emitting a single JSON blob, per issue #65. Strictly read-only — no
# gh/git subcommand here mutates anything, matching t-status's own "never writes"
# invariant (AGENTS.md, .claude/skills/t-status/SKILL.md).
#
# This script only fetches and assembles already-mechanical facts (raw issue/PR data,
# a computed CI bucket, a computed latest review, a computed truncation flag). It makes
# no refusal or judgment call — blocked/unblocked, protected-surface, scope-overlap, and
# intent-drift decisions all stay in t-status's own prose, reading the fields below.
#
# Usage: .t-workflow/scripts/status-snapshot.sh
#   No arguments. Run from anywhere inside the repository, with `gh` authenticated.
# Exit 0 = one JSON object printed on stdout. Exit 2 = bad usage (no live call made).
#
# Internal test hook, issue #147: `--test-stale-match <glob>...`, reading changed
# paths one per line on stdin, prints "true" if any path matches any glob (bash's
# `==` pattern operator, the same style protected-paths.sh uses) and "false"
# otherwise. This is the exact matching rule the staleness computation below applies
# per verification entry — pulled out here, and called by that computation via `"$0"
# --test-stale-match` rather than duplicated inline, so plumbing-test.sh can
# fixture-test the rule directly (no live git state needed for this part) instead of
# only exercising it through a real `git diff`. A /t-review pass on this task's own
# PR caught a real bug here — the match's two branches were once swapped, so a diff
# that touched a declared scope glob was read as *not* stale — because nothing tested
# this rule in isolation; this hook is the fix for that gap, not only for the bug.
# Never documented as a public interface and never called by /t-status itself.
set -euo pipefail

if [ "${1:-}" = "--test-stale-match" ]; then
  shift
  matched="false"
  while IFS= read -r p; do
    [ -z "$p" ] && continue
    for g in "$@"; do
      case "$p" in
        $g) matched="true" ;;
      esac
    done
  done
  printf '%s\n' "$matched"
  exit 0
fi

if [ "$#" -gt 0 ]; then
  echo "usage: .t-workflow/scripts/status-snapshot.sh   (no arguments)" >&2
  exit 2
fi

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
cd "$root"

work=$(mktemp -d "${TMPDIR:-/tmp}/status-snapshot.XXXXXX")
trap 'rm -rf "$work"' EXIT HUP INT TERM

# --- issues: one bulk call serves both initiatives and tasks (ADR-003's blockedBy) ---
# `parent` is added for issue #147: an origin-inheritance read (a task's own `## Origin`
# section, falling back to its parent initiative's) needs to know which initiative a
# task belongs to, and the bulk call already returns every candidate parent's own body
# in this same array — no second call.
issue_limit=1000
gh issue list --state open --limit "$issue_limit" \
  --json number,title,body,labels,blockedBy,updatedAt,subIssuesSummary,parent \
  > "$work/issues-open.json"

gh issue list --state closed --label cancelled --limit 100 --json number,title \
  > "$work/issues-cancelled.json"

# --- PRs: one all-state call carries files/reviews/checks for every PR at once ---
# `headRefOid` (issue #147): the exact head commit, needed to judge whether a review
# or a verification entry is current or stale (docs/architecture/verification.md,
# docs/architecture/collaboration-state.md). Its *timestamp* is deliberately NOT
# fetched here via GraphQL's `commits` field — at this call's own `--limit 200`, `gh
# pr list --json ...,commits` traverses each commit's nested `authors` connection
# across every PR at once and exceeds GitHub's 500,000-node GraphQL ceiling (confirmed
# empirically against this repo). The per-task preprocessing loop below computes it
# instead, locally, from the branch it has already fetched (`git show -s --format=%cI
# <sha>`) — no GraphQL call at all, and no ceiling to hit regardless of PR count.
# `comments` (already part of `forge:pr-reviews`'s documented contract): where a
# project's own preview tooling may have posted preview evidence
# (docs/architecture/collaboration-state.md's own preview-evidence convention) — read
# here, never fetched from anywhere external.
pr_limit=200
gh pr list --state all --limit "$pr_limit" \
  --json number,title,state,isDraft,headRefName,url,createdAt,mergeable,mergeStateStatus,reviews,files,statusCheckRollup,headRefOid,comments \
  > "$work/prs-all.json"

# --- issue #147: fetch each open task's own record off its own branch, for the
# `## Verification`/`## Feedback` sections no bulk tracker call can see (they live only
# in the record, docs/tasks/README.md) --------------------------------------------------
# Only open PRs whose head branch names an already-open, non-initiative task — never
# every PR in the repository, and never a working-tree checkout (a `git show` of a
# blob, same invariant `/t-status` already holds for local branches/worktrees).
jq -c '[.[] | select([.labels[].name] | index("initiative") == null) | .number]' \
  "$work/issues-open.json" > "$work/open-task-numbers.json"

jq -c --slurpfile nums "$work/open-task-numbers.json" '
  ($nums[0]) as $nums |
  [.[] | select(.state == "OPEN") |
    . as $pr |
    ($pr.headRefName | capture("^wip/(?<id>[0-9]+)-")? ) as $m |
    select($m != null) |
    ($m.id | tonumber) as $tid |
    select($nums | index($tid) != null) |
    {number: $tid, headRefName: $pr.headRefName, headRefOid: $pr.headRefOid}
  ]
' "$work/prs-all.json" > "$work/task-prs.json"

: > "$work/task-extra.jsonl"
task_pr_count=$(jq 'length' "$work/task-prs.json")
if [ "$task_pr_count" -gt 0 ]; then
  branch_list=$(jq -r '.[].headRefName' "$work/task-prs.json")
  # A single batched fetch, not one per branch: cheap, and every ref lands in
  # refs/remotes/origin/* via this repo's default fetch refspec, so `git show
  # origin/<branch>:<path>` below needs no working-tree checkout at all.
  # shellcheck disable=SC2086
  git fetch --quiet origin $branch_list 2>/dev/null || true

  while IFS= read -r row; do
    tid=$(printf '%s' "$row" | jq -r '.number')
    headRef=$(printf '%s' "$row" | jq -r '.headRefName')
    headOid=$(printf '%s' "$row" | jq -r '.headRefOid')
    slug="${headRef#wip/"$tid"-}"
    bucket=$(printf '%06d' $(( (tid / 100) * 100 )))
    recPath="docs/tasks/$bucket/$tid-$slug.md"

    record_file="$work/record-$tid.md"
    issue_file="$work/issue-$tid.md"
    : > "$record_file"
    headCommitTime="null"
    if git rev-parse -q --verify "origin/$headRef" >/dev/null 2>&1; then
      git show "origin/$headRef:$recPath" > "$record_file" 2>/dev/null || : > "$record_file"
      # Committer date, ISO 8601 — computed locally from the branch this loop already
      # fetched, so a review's or a verification entry's currency can be judged
      # without the GraphQL `commits` field (see the bulk PR fetch's own comment on
      # why that field is never requested there).
      t=$(git show -s --format=%cI "$headOid" 2>/dev/null || true)
      [ -n "$t" ] && headCommitTime="\"$t\""
    fi
    jq -r --argjson tid "$tid" '.[] | select(.number == $tid) | .body' "$work/issues-open.json" \
      > "$issue_file" 2>/dev/null || : > "$issue_file"

    parsed=$("$here/parse-task-record.sh" "$record_file" "$issue_file" 2>/dev/null) || parsed='{"verification":[],"feedbackLastClassification":null}'

    # Staleness (docs/architecture/verification.md's own formula, unchanged from
    # /t-ship precondition 7 and /t-drive): only the live `git diff` against a
    # `scope:` exemption needs git, so it stays here rather than in the pure parser
    # above. `false` for pending/rejected (irrelevant — they already block on state
    # alone); `true` whenever the recorded revision is missing/unreachable or differs
    # from the head with no scope exemption; `false` only when the revision matches
    # the head, or the diff since it touches none of the declared scope globs.
    verif_count=$(printf '%s' "$parsed" | jq '.verification | length')
    stale_json="["
    i=0
    while [ "$i" -lt "$verif_count" ]; do
      entry_state=$(printf '%s' "$parsed" | jq -r ".verification[$i].state")
      entry_revision=$(printf '%s' "$parsed" | jq -r ".verification[$i].revision")
      stale="false"
      if [ "$entry_state" = "verified" ] || [ "$entry_state" = "risk-accepted" ]; then
        if [ "$entry_revision" = "null" ] || [ -z "$entry_revision" ]; then
          stale="true"
        elif [ "$entry_revision" = "$headOid" ]; then
          stale="false"
        else
          scope_count=$(printf '%s' "$parsed" | jq ".verification[$i].scope | length")
          if [ "$scope_count" -eq 0 ]; then
            stale="true"
          elif git rev-parse -q --verify "${entry_revision}^{commit}" >/dev/null 2>&1 \
               && git rev-parse -q --verify "${headOid}^{commit}" >/dev/null 2>&1; then
            # docs/architecture/verification.md's own formula: stale UNLESS the diff
            # touches NONE of the declared scope globs. Delegated to this script's
            # own `--test-stale-match` hook (top of file) rather than duplicated
            # inline, so the exact matching rule is fixture-tested in isolation
            # (fixed a /t-review high finding: an earlier version of this loop had
            # its two branches swapped, reading a diff that touched a declared scope
            # glob as *not* stale — a false "nothing to do here" reading that nothing
            # here tested).
            changed=$(git diff --name-only "$entry_revision" "$headOid" 2>/dev/null || true)
            mapfile -t globs < <(printf '%s' "$parsed" | jq -r ".verification[$i].scope[]")
            stale=$(printf '%s\n' "$changed" | "$here/status-snapshot.sh" --test-stale-match "${globs[@]}")
          else
            stale="true"
          fi
        fi
      fi
      [ "$i" -gt 0 ] && stale_json+=","
      stale_json+="$stale"
      i=$((i + 1))
    done
    stale_json+="]"

    parsed=$(printf '%s' "$parsed" | jq -c --argjson stale "$stale_json" '
      .verification = [range(0; (.verification | length)) as $i | .verification[$i] + {stale: $stale[$i]}]
    ')

    jq -cn --argjson tid "$tid" --argjson parsed "$parsed" --argjson headCommitTime "$headCommitTime" \
      '{number: $tid, headCommitTime: $headCommitTime} + $parsed' \
      >> "$work/task-extra.jsonl"
  done < <(jq -c '.[]' "$work/task-prs.json")
fi

jq -cs '.' "$work/task-extra.jsonl" > "$work/task-extra.json"

# --- local git state: no gh call, just this checkout and its worktrees ---
branch=$(git rev-parse --abbrev-ref HEAD)
if [ -z "$(git status --porcelain)" ]; then clean=true; else clean=false; fi
git branch --list 'wip/*' --format='%(refname:short)' > "$work/local-branches.txt"
git worktree list --porcelain | awk '
  /^worktree / { path = $2; branch = "" }
  /^branch /   { branch = $2; sub("^refs/heads/", "", branch) }
  /^$/         { if (path != "" && branch != "") print path "\t" branch; path = ""; branch = "" }
  END          { if (path != "" && branch != "") print path "\t" branch }
' > "$work/worktrees.tsv"

jq -Rn '
  [inputs | select(length > 0) | split("\t") | {path: .[0], branch: .[1]}]
' "$work/worktrees.tsv" > "$work/worktrees.json"

jq -Rn '[inputs | select(length > 0)]' "$work/local-branches.txt" > "$work/local-branches.json"

jq -n \
  --slurpfile issues "$work/issues-open.json" \
  --slurpfile cancelled "$work/issues-cancelled.json" \
  --slurpfile prs "$work/prs-all.json" \
  --slurpfile localBranches "$work/local-branches.json" \
  --slurpfile worktrees "$work/worktrees.json" \
  --slurpfile taskExtra "$work/task-extra.json" \
  --arg branch "$branch" \
  --argjson clean "$clean" \
  --argjson issueLimit "$issue_limit" \
  --argjson prLimit "$pr_limit" \
  '
  ($issues[0]) as $issues |
  ($cancelled[0]) as $cancelled |
  ($prs[0]) as $prs |
  ($localBranches[0]) as $localBranches |
  ($worktrees[0]) as $worktrees |
  ($taskExtra[0]) as $taskExtra |

  # a PR whose head branch matches wip/<id>-* — for correlating a local/worktree branch
  def pr_for_branch($b): $prs | map(select(.headRefName == $b)) | sort_by(.createdAt) | last;

  # CI contract (forge:pr-checks): distinguish "no CI configured" from failing/pending.
  # Enumerate every terminal non-passing conclusion the checkRun API defines, not just
  # FAILURE — CANCELLED/TIMED_OUT/ACTION_REQUIRED/STARTUP_FAILURE/STALE are all
  # `status: COMPLETED` too, so checking only FAILURE let those fall through to "pass".
  def ci_state:
    ["FAILURE", "CANCELLED", "TIMED_OUT", "ACTION_REQUIRED", "STARTUP_FAILURE", "STALE"] as $failing |
    if (.statusCheckRollup | length) == 0 then "none configured"
    elif ([.statusCheckRollup[] | select(.status == "COMPLETED" and ([.conclusion] | inside($failing)))] | length) > 0 then "fail"
    elif ([.statusCheckRollup[] | select(.status != "COMPLETED")] | length) > 0 then "pending"
    else "pass" end;

  # forge:pr-reviews contract: the caller finds the latest review by submittedAt.
  # issue #147 adds `readiness` (the literal "readiness: ready"/"readiness: not-ready"
  # line every review already carries, `.claude/skills/t-review/SKILL.md`) and
  # `current` (the head commit of this PR landed at or before the review -- the same
  # comparison `check-review-gate.sh` already makes) so /t-status never has to
  # re-parse a review body itself. `$headTime` is the timestamp of the head commit --
  # computed locally by the preprocessing loop above (never via the GraphQL `commits`
  # field; see the comment on the bulk PR fetch for why), `null` when unavailable (no
  # correlated task, or the branch could not be fetched) -- a review is then trusted
  # as current rather than guessed stale, the same "nothing to compare against"
  # default every other gate in this repo already falls back to.
  def latest_review($headTime):
    (.reviews | sort_by(.submittedAt) | last) as $r |
    if $r == null then null
    else
      {body: $r.body, submittedAt: $r.submittedAt, state: $r.state,
       readiness: (if ($r.body | test("(?m)^readiness: ready\\s*$")) then "ready"
                   elif ($r.body | test("(?m)^readiness: not-ready\\s*$")) then "not-ready"
                   else null end),
       current: (if $headTime == null then true else ($r.submittedAt >= $headTime) end)}
    end;

  # issue #147, the preview-evidence convention docs/architecture/collaboration-state.md
  # documents: preview evidence is a PR comment carrying a flat JSON object matching
  # the shape docs/adapters/PREVIEW.md names (commit/status at minimum). Only a
  # non-nested object is matched -- that shape has no nested braces -- and only the
  # one naming the current head commit of this PR counts; an evidence object for an
  # older commit is exactly as informative as none (docs/adapters/PREVIEW.md "evidence
  # is tied to an exact commit"). No comment matching at all is `null` -- matching
  # Done-when: "missing optional external systems do not prevent status reporting."
  def preview_evidence:
    .headRefOid as $head |
    [(.comments // [])[].body // "" |
      [match("\\{[^{}]*\\}"; "g").string] | .[]?] as $candidates |
    [$candidates[] | (try fromjson catch null) |
      select(. != null and (.commit? // null) == $head and (.status? // null) != null)] |
    sort_by(.commit) | last // null;

  # issue #147: merge in each open task own parsed record (## Verification, ##
  # Feedback -- the preprocessing above fetched and parsed these off the task branch;
  # a task with no open PR, or whose record could not be read, simply has none here)
  # by task number.
  def record_for($tid): $taskExtra | map(select(.number == $tid)) | first;

  {
    initiatives: [$issues[] | select([.labels[].name] | index("initiative") != null)
      | {number, title, body, subIssuesSummary}],

    tasks: {
      truncated: (($issues | length) == $issueLimit),
      items: [$issues[] | select([.labels[].name] | index("initiative") == null)
        | {number, title, body, labels: [.labels[].name], blockedBy: .blockedBy.nodes, updatedAt,
           parent: (if .parent == null then null else {number: .parent.number, title: .parent.title} end)}]
    },

    prs: {
      truncated: (($prs | length) == $prLimit),
      open: [$prs[] | select(.state == "OPEN")
        | (.headRefName | capture("^wip/(?<id>[0-9]+)-")? ) as $m
        | (if $m == null then null else record_for($m.id | tonumber) end) as $rec
        | {number, title, isDraft, headRefName, url, createdAt, mergeable, mergeStateStatus,
           files: [.files[].path], ciState: ci_state, latestReview: latest_review($rec.headCommitTime // null),
           headRefOid, headCommitTime: ($rec.headCommitTime // null), previewEvidence: preview_evidence,
           record: $rec}]
    },

    local: {
      branch: $branch,
      clean: $clean,
      localWipBranches: [$localBranches[]
        | {name: ., pr: (pr_for_branch(.) | if . == null then null else {number, state, url} end)}],
      worktrees: [$worktrees[]
        | {path, branch, pr: (pr_for_branch(.branch) | if . == null then null else {number, state, url} end)}]
    },

    cancellations: {
      truncated: (($cancelled | length) == 100),
      items: $cancelled
    }
  }
  '
