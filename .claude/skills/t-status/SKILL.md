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
cheap: tracker/forge queries and light greps, no full-file reads of the working tree. Two
warnings below do cost a per-PR call each (the protected-surface check needs
`forge:pr-files`, and the edited-after-opening check needs the issue's and PR's
timestamps); they are worth it, and they are the only ones that are. Resolve every
`tracker:*` / `forge:*` operation via `docs/adapters/TRACKER.md` and
`docs/adapters/FORGE.md` (GitHub by default).

## Procedure

1. **Initiatives:** `tracker:list-initiatives` — for each, its
   task-list completion (ticked / total). On a repository whose labels were never
   bootstrapped, a tracker silently drops a label its issue form asks for, so a
   hand-opened initiative can carry none and go unlisted here: an empty result means
   "none labelled", not "none exist". Say which, and recommend
   `scripts/github-bootstrap.sh`.
2. **Tasks:** `tracker:list-open` (excluding initiatives) — number,
   title, and blocked state: a task whose body's every `Blocked-by: #n` references a
   *closed* issue is **unblocked**. Count the marker in both shapes — the inline
   `Blocked-by: #n`, and a bare `#n` under a `### Blocked by` heading, which is how an
   issue form renders it. Highlight unblocked tasks with no branch as "ready to
   pick up". The operation's contract requires a complete scan; a silently truncated list
   reports a quiet pipeline that is not quiet.
3. **PRs:** `forge:pr-list` (open) — draft vs ready, latest review verdict line
   (`readiness: …`) from `forge:pr-reviews` if present, CI state via `forge:pr-checks`.
4. **Local:** current branch and whether the tree is clean; stale `wip/*` and `fix/*`
   branches whose PR is merged or closed; worktrees (`git worktree list`) — flag any whose
   branch's PR is merged or closed (one somebody forgot to remove).
5. **Meaning-free fixes:** merged PRs from `fix/*` branches
   (`forge:pr-list`, merged, filtered on the head-branch prefix). The delta against the
   count noted at the last retro is the ADR-001 creep signal. Find that baseline in the
   most recent record whose task was titled `Workflow retro: <date>`; when none exists
   yet, say the signal has no baseline rather than reporting a delta against zero.
6. **Cancellations:** `tracker:list-cancelled` — the count and the titles. The reason for each is in its close comment
   (ADR-001); this skill does not fetch comments. On a repository bootstrapped before
   `/t-cancel` existed the label was never created and the query returns empty: that is
   correct, not an error.

## Warnings to surface

- A task in progress (branch or PR exists) whose blocker is still open — or whose blocker
  was closed as **cancelled**, which is abandonment, not satisfaction.
- A PR with `readiness: ready` sitting unmerged — name it as awaiting `/t-ship`.
- **An open PR touching a protected surface with no review comment** — decide protection
  by piping `forge:pr-files` through `bash scripts/protected-paths.sh --stdin`, never by
  reading `CONSTITUTION.md` §3 by eye. Exit 2 means the file list came back empty; report
  that rather than counting the PR as unprotected. Review is optional in general and required for those paths, so this is the
  one missing review that blocks shipping.
- Scope lines of two open tasks that overlap (cheap string comparison; name the pair).
- An issue whose body was edited after its PR opened (intent belongs in the record once
  work starts). **One exemption, or this warning fires on the pipeline's own prescribed
  path:** when work grows onto a protected surface, `/t-work` and `/t-ship` send the task
  to `/t-plan`, which writes a `## Plan` section to the issue body by design — adding one
  the first time, and *replacing* it on any re-plan. An edit that only added or only
  replaced that section is expected — say so rather than flagging it. An edit that changed
  Goal, Done when, Scope or Non-goals is not exempt, whatever else it touched: that is
  the intent drift this warning exists to catch.
- A task branch with no open PR and no merged commit — work that stalled after the branch
  was cut.

## Output

Plain prose per AGENTS.md §Communication — status lines say what things mean in ordinary
language before any internal terminology.

A compact table per section, then the warnings, then one line: the single most useful next
action (e.g. "`/t-ship 154` — reviewed and green" or "`/t-work 156` — unblocked since
#153 closed").
