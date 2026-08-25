# 36 — t-wtree: base new worktrees on origin/main, not the primary checkout's local main
Issue: #36

## Asked
`/t-wtree` step 4 currently requires fast-forwarding the primary checkout's local `main`
branch before creating a new worktree for the task. That fast-forward fails whenever the
primary checkout has uncommitted work sitting on some other branch — a normal state for a
user who doesn't always work inside a task worktree. The step doesn't actually need the
primary checkout's `main` at all: `origin/main` is already fetched in step 2 and is a
perfectly good base for a new worktree's branch. Basing off `origin/main` instead removes
the dependency on the primary checkout's state entirely, so `/t-wtree` stops failing due
to unrelated uncommitted work elsewhere in the repo.

## Done when
- `.claude/skills/t-wtree/SKILL.md` step 4 creates new worktree branches from `origin/main`
  (post-fetch) instead of requiring a fast-forward of the local `main` branch.
- The "fast-forward a behind-only main... stop and report if it is ahead or diverged"
  requirement is removed from step 4, since it no longer applies.
- Manually verified: running `/t-wtree <id>` for a new branch succeeds while the primary
  checkout has uncommitted changes on an unrelated branch.

## Explicitly not
- No change to how `/t-wtree` reuses existing branches/worktrees (steps 2-3 unaffected).
- No change to the sibling-path naming convention or reuse-before-create rule.

## Decisions made along the way
- none

## Deviations / notes
- none
