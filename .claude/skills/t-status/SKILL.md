---
name: t-status
description: Read-only pipeline overview — open initiatives and tasks, blocked/unblocked state, PRs and their review/CI state, plus warnings. Use when asked what is in flight, what to pick up next, or "t-status".
# Narrows the tool set to match the read-only promise below: no Edit, no Write. Bash is
# needed for the tracker/forge queries and git reads, and remains a write channel in
# principle — every command this skill names is a read, and that rule still governs;
# this line removes the easy paths.
allowed-tools: Read, Grep, Glob, Bash
---

# Pipeline status

Report what is in flight and where it sits. This skill **never writes, comments, labels,
or asks for confirmation** — that is what makes it safe to run at any moment. Keep it
cheap: one script call, light greps, no full-file reads of the working tree.

## Procedure

0. **Fetch once.** `bash .t-workflow/scripts/status-snapshot.sh` — the one live call this
   skill makes; it shells out to `gh`/`git` for every `tracker:*`/`forge:*` operation
   below and emits a single JSON blob (`initiatives`, `tasks`, `prs`, `local`,
   `cancellations`). Every step from here on reads a field of that blob — resolve
   `tracker:*`/`forge:*` operation names below against `docs/adapters/TRACKER.md` and
   `docs/adapters/FORGE.md` (GitHub by default) only to understand what the script
   fetched, never by issuing the call yourself. The script is strictly read-only,
   matching this skill's own "never writes" invariant.
1. **Initiatives:** `.initiatives[]` (`tracker:list-initiatives`, folded into the one
   fetch) — for each, its sub-issue completion from the native `subIssuesSummary` field
   (closed / total). On a repository whose labels were never bootstrapped, a tracker
   silently drops a label its issue form asks for, so a hand-opened initiative can carry
   none and go unlisted here: an empty result means "none labelled", not "none exist".
   Say which, and recommend `.t-workflow/scripts/github-bootstrap.sh`.
2. **Tasks:** `.tasks.items[]` (`tracker:list-open`, initiatives already excluded) —
   number, title, and blocked state: a task whose every entry in the `blockedBy` field
   is a *closed* issue is **unblocked** (ADR-003 — the same bulk field the fetch already
   carries, no per-task query needed). Highlight unblocked tasks with no matching entry
   in `.local.localWipBranches[]` as "ready to pick up". `.tasks.truncated` true means an
   incomplete scan — say so; it is a quiet pipeline that is not quiet, never "none
   found".
3. **PRs:** `.prs.open[]` (`forge:pr-list`, `forge:pr-files`, `forge:pr-reviews`, and
   `forge:pr-checks` all folded into the one fetch) — draft vs ready (`.isDraft`),
   latest review verdict line (`readiness: …`, parsed out of `.latestReview.body` when
   `.latestReview` is not null), CI state from `.ciState` (`"pass"` / `"fail"` /
   `"pending"` / `"none configured"` — already the pass/fail/pending/no-CI distinction
   `forge:pr-checks`'s contract asks for). `.prs.truncated` true means an incomplete
   scan, reported as one.
4. **Local:** `.local.branch` and `.local.clean` — current branch and whether the tree
   is clean. `.local.localWipBranches[]` — every local `wip/*` branch with its PR (`.pr`,
   or `null` if none was ever opened): `.pr` is `null` is the "stalled after the branch
   was cut" case (Warnings below); a `.pr.state` of `MERGED` or `CLOSED` is a stale
   branch nobody removed. `.local.worktrees[]` — every registered worktree
   (`git worktree list`) with the same `.pr` correlation; flag any whose `.pr.state` is
   `MERGED` or `CLOSED` the same way.
5. **Cancellations:** `.cancellations.items[]` (`tracker:list-cancelled`) — the count
   and the titles. The reason for each is in its close comment (ADR-001); this skill
   does not fetch comments. On a repository bootstrapped before `/t-cancel` existed the
   label was never created and this is empty: that is correct, not an error.
   `.cancellations.truncated` true means an incomplete scan, reported as one.

## Warnings to surface

- A task in progress (branch or PR exists) whose blocker is still open — or whose blocker
  was closed as **cancelled**, which is abandonment, not satisfaction.
- A PR with `readiness: ready` sitting unmerged — name it as awaiting `/t-ship`.
- **An open PR touching a protected surface with no review comment** — decide protection
  by piping a PR's `.files[]` (already fetched, per-PR, on `.prs.open[]`) through
  `bash .t-workflow/scripts/protected-paths.sh --stdin`, never by reading
  `CONSTITUTION.md` §3 by eye. Exit 2 means the file list came back empty; report that
  rather than counting the PR as unprotected. Review is optional in general and required
  for those paths, so this is the one missing review that blocks shipping.
- Scope lines of two open tasks that overlap (cheap string comparison over `.tasks.items[].body`; name the pair).
- An issue whose body (`.tasks.items[].updatedAt`) was edited after its matching PR
  opened (`.prs.open[].createdAt`, matched by `headRefName` prefix `wip/<id>-`) — intent
  belongs in the record once work starts. **One exemption, or this warning fires on the
  pipeline's own prescribed path:** when work grows onto a protected surface, `/t-work`
  and `/t-ship` send the task to `/t-plan`, which writes a `## Plan` section to the issue
  body by design — adding one the first time, and *replacing* it on any re-plan. An edit
  that only added or only replaced that section is expected — say so rather than
  flagging it. An edit that changed Goal, Done when, Scope or Non-goals is not exempt,
  whatever else it touched: that is the intent drift this warning exists to catch.
- A task branch with no open PR and no merged commit — `.local.localWipBranches[]`
  entries whose `.pr` is `null` — work that stalled after the branch was cut.

## Output

Plain prose per AGENTS.md §Communication — status lines say what things mean in ordinary
language before any internal terminology.

A compact table per section, then the warnings, then one line: the single most useful next
action (e.g. "`/t-ship 154` — reviewed and green" or "`/t-work 156` — unblocked since
#153 closed").
