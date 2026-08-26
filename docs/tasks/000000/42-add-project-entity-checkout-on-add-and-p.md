# 42 — Add Project entity, checkout-on-add, and Project CRUD API
Issue: #42 · Part of: #41

## Asked
Add a Project entity to the Spring Boot engine (id, name, git repo URL, workarea
path, default branch). Adding a project (POST) triggers a checkout of the repo's
main branch into a per-project subfolder under a workarea-root property,
asynchronously — the project starts in a "cloning" state and moves to "ready" once
the checkout completes, or "failed" if it errors. The project name is optional on
create: if left blank, it is derived from the git URL.

## Done when
- A Project can be created via API with a git repo URL and an optional name; if the
  name is blank, one is derived from the URL and persisted.
- Creating a project clones the repo's main branch into `<workarea-root>/<slug>`
  without blocking the request; project status reflects cloning/ready/failed.
- A failed clone can be retried or the project deleted via API.
- `GET /api/projects` lists all projects with their status.

## Explicitly not
- Nesting existing issue/worktree endpoints under project id — #43.
- Sidenav/UI work — #44, #45.

## Decisions made along the way
- The issue's Done-when refers to "the existing workarea-root property", but no
  property by that name exists in the codebase — `locklane.project-root` is the
  only existing root, and it names Locklane's own repo checkout (the base for the
  existing single-project worktree feature, #15/#20), not a place for arbitrary
  external repos. Added a new `locklane.workarea-root` property
  (`${locklane.data-dir}/workareas` by default) rather than overloading
  `project-root`, so the new multi-project checkouts stay independent of the
  existing worktree convention (haninaguib, 2026-08-25).
- `default_branch` is not a create-time input (only git URL and optional name are,
  per Done-when) — it is discovered by the checkout itself: `git clone` follows the
  remote's HEAD to whatever its default branch actually is (not hardcoded to
  "main", since arbitrary external repos may default to "master" or anything else),
  and the resulting checked-out branch name is read back and stored. The column is
  nullable and stays NULL until the clone succeeds (haninaguib, 2026-08-25).
- Placed `ProjectRecord`/`ProjectRepository`/`ProjectCheckoutService`/
  `ProjectController` in `dev.locklane.engine.persistence`, matching where
  `WorktreeSessionRecord`/`WorktreeSessionRepository`/`WorktreeCreationService`/
  `WorktreeController` already live — this codebase groups a session/entity concern
  by its persisted-state package rather than carving a new domain package per
  feature (haninaguib, 2026-08-25).
- Cloning runs on an injected `java.util.concurrent.Executor` (a virtual-thread
  executor in production, `Runnable::run` in tests) rather than `@Async`/a fixed
  thread pool — the codebase has no existing `@Async` usage to follow, and an
  injected `Executor` keeps the async clone path deterministically testable
  without sleeps or polling (haninaguib, 2026-08-25).
- A workarea directory name collision (two projects deriving the same slug) gets a
  numeric suffix (`<slug>-2`, `<slug>-3`, …) rather than failing the create — same
  spirit as the existing `<issueNumber>-main-<shortId>` disambiguation in
  `WorktreeCreationService` (haninaguib, 2026-08-25).
- `/api/projects` (all methods) requires authentication in `SecurityConfig`, same
  as the other state-changing endpoints (`/api/issues/*/worktrees`, `/api/consoles`)
  (haninaguib, 2026-08-25).

## Deviations / notes
- none
