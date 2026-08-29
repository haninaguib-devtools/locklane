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
- Pointer line lands in `CONSTITUTION.md` §4's `<!-- local -->` block, not
  `AGENTS.md` — corrected after the first pass; see Deviations below (`/t-plan`
  re-plan, see issue #319's `## Plan`).
- New ADR is `docs/adr/006-*.md` (next free number after 005) (`/t-plan`).

## Deviations / notes
- **Re-plan mid-implementation, caught by CI's `manifest` job, not by review.** The
  first pass of `/t-plan` chose `AGENTS.md` over `CONSTITUTION.md` for the ADR pointer
  line ("`AGENTS.md` — the pointer line (§2.3); not `CONSTITUTION.md` — ADR-005's own
  rule already lives in `AGENTS.md`'s Conventions section... keeping `CONSTITUTION.md`
  itself untouched"). That reasoning didn't account for this repo being a pinned
  consumer of the `t-workflow` template (`.template-manifest.json`): per
  `docs/architecture/local-slots.md`, only `CONSTITUTION.md` §4 and `AGENTS.md`
  §Checks item 1 are per-repo local slots — everything else in either file is
  template-owned and hashed into the manifest. The sentence added to `AGENTS.md`'s
  Conventions section landed outside its one slot, and CI's `manifest` job
  (`./.t-workflow/scripts/check-manifest.sh`, `.github/workflows/ci.yml`) failed with
  `DRIFT: AGENTS.md` after the PR was opened and reviewed `readiness: ready`.
  Corrected by: reverting the `AGENTS.md` edit entirely, and instead adding the
  pointer as a second numbered item inside `CONSTITUTION.md` §4's existing local
  block — the same slot that already cites this app's own ADR-002 for the
  worktree/PTY architecture, one line above. Re-ran `/t-plan 319`, which replaced the
  `## Plan` section's Allowed paths (was: `AGENTS.md`, not `CONSTITUTION.md`; now:
  `CONSTITUTION.md` §4's local block only, not `AGENTS.md`) and added the manifest
  check to Validation. Approved in the moment by this task's own driver (`/t-drive`
  317, acting per its ADR-004 delegated authority) — no unrelated content changed.
  `./.t-workflow/scripts/check-manifest.sh` now reports `OK: 48 template-owned
  file(s) match the pinned manifest`.
