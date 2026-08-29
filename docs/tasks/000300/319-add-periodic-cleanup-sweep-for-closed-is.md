# 319 — Add periodic cleanup sweep for closed-issue worktrees, with ADR-005 carve-out
Issue: #319 · Part of: #317

## Asked
Add a background job that deletes a console-created git worktree once it is safe to do
so: its issue is confirmed closed, the worktree's git status is clean, and no live
console session has its working directory inside it. Reuse the existing `GhIssueCache`
for issue state — no new GitHub API calls. Because this introduces automatic worktree
removal, it is a deliberate, scoped exception to ADR-005 ("worktrees are left alone
permanently, removed by hand only") and must be recorded as such.

## Done when
- A scheduled job periodically evaluates each console-created worktree.
- A worktree is deleted only when all hold: its issue's cached state
  (`GhIssueCache`/`ctx.cache().issue(number)`) is `CLOSED`; `git status --porcelain` in
  that worktree is empty; no live PTY/console session (`SessionRegistry`) has its
  working directory inside it.
- A worktree whose issue is not found in the cache, is open, is dirty, or has an
  attached session is left untouched — never force-removed.
- The sweep is invokable both on its schedule and programmatically, so a later task can
  add a manual "run now" trigger without duplicating the guard logic.
- The ADR-005 exception is recorded in `docs/adr/` (new ADR, referencing ADR-005) with
  its operative one-line rule added to `CONSTITUTION.md`/`AGENTS.md` per
  `CONSTITUTION.md` §2.3.

## Explicitly not
- Deleting a worktree for any reason other than the confirmed-closed + clean +
  unattached condition above.
- Deleting the task's git branch (worktree only, per scope).
- A manual "run now" trigger or any project-page UI — split to #320 (formally
  `blockedBy` #319; this task only makes the sweep programmatically invokable, it does
  not add a caller for that beyond the scheduler).
- Giving project-console sessions their own worktree — a separate concern, #314.

## Decisions made along the way
- Pointer line lands in `AGENTS.md`, not `CONSTITUTION.md` — ADR-005's own rule
  already lives in `AGENTS.md`'s Conventions section, so the narrower automatic-deletion
  exception is documented as a qualification next to it; `CONSTITUTION.md` itself stays
  untouched (`/t-plan`, see issue #319's `## Plan`).
- New ADR is `docs/adr/006-*.md` (next free number after 005) (`/t-plan`).

## Deviations / notes
- none
