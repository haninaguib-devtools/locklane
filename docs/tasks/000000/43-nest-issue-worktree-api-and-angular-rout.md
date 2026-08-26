# 43 — Nest issue/worktree API and Angular routes under project id
Issue: #43 · Part of: #41

## Asked
Issue numbers can collide across projects, so every issue- and worktree-scoped route
must carry the project id explicitly — no bare `/issues/:id` can remain. Renest the
existing API endpoints under `/api/projects/{projectId}/...` and the Angular routes
under `/projects/:projectId/...`, updating every caller.

## Done when
- All issue/worktree API endpoints require and use `projectId` in their path.
- All Angular routes for issues/worktrees carry `:projectId`.
- No route or endpoint resolves an issue/worktree without a project id in scope.
- Existing tests updated/passing against the new paths.

## Explicitly not
- Project entity/checkout itself — #42, already delivered.
- Sidenav UI changes — #44.

## Decisions made along the way
- Confirmed scope directly with the human before starting (three real forks, each
  changing the size/shape of the task):
  1. **Whether "in scope" means the underlying data follows project id, or just the
     URL.** Landed on: worktree/session creation and location follow project id for
     real (each project's own cloned repo); issue/PR data keeps coming from the one
     shared `gh` call for every project, matching the precedent set by #48 (its
     record: "today issues/PRs come from one shared repo... no per-project or
     per-user GitHub identity to scope by yet... A follow-up issue ('Store an
     encrypted per-project GitHub token and scope issue/PR fetches through it')...
     recommended for the human to open"). That follow-up was never opened —
     confirmed via `gh issue list --search`, nothing matches. Recommending it again
     in this task's closing report (haninaguib, 2026-08-26).
  2. **Whether the running app should keep working through the #44/#45 gap** (no
     project-picker UI exists yet). Landed on: yes — auto-bootstrap the engine's own
     existing checkout as a Project on first run (mirrors `UserBootstrapper`'s
     precedent exactly), so the app keeps behaving as it does today with no visible
     change until #44/#45 land (haninaguib, 2026-08-26).
- Worktree id convention changes from `<issueNumber>-<slug>` to
  `<projectId>-<issueNumber>-<slug>` (main-checkout sessions:
  `<projectId>-<issueNumber>-main-<shortId>`) — the opaque session/DB key needs
  disambiguating by project now that worktrees genuinely live under different
  project checkouts; the git branch name itself (`wip/<issueNumber>-<slug>`) is
  unchanged, since each project is its own independent repo with its own branch
  namespace — no cross-project collision risk there (haninaguib, 2026-08-26).
- `IssueController`'s own methods needed no code changes beyond the class-level
  `@RequestMapping` — Spring MVC doesn't require every `{projectId}` template
  variable to be bound as a method parameter, and since issue data stays global
  (decision 1 above), there's nothing for the handlers to do with it. Chose not to
  add a projectId-exists check on issue-read endpoints (unlike worktree creation,
  where it's load-bearing — resolving a nonexistent project's workarea path would
  NPE) to keep this consistent with "issue data isn't really project-scoped yet"
  rather than half-validating it (haninaguib, 2026-08-26).
- `SecurityConfig`'s existing `/api/projects/**` matcher (from #42) would have
  swept up the newly-nested `/api/projects/{projectId}/issues/**` and silently
  required login for issue browsing, which was open before #42 and #43. Replaced
  the wildcard with the exact set of project-CRUD and worktree/console patterns
  that actually need auth, relying on Ant-style `*` not crossing `/` to keep issue
  browsing open (haninaguib, 2026-08-26).
- `ConsolesController`'s cross-issue listing is nested and scoped to one project
  too (`/api/projects/{projectId}/consoles`) — it resolves worktree sessions the
  same way `WorktreeController` does, so leaving it unscoped would have been
  exactly the "endpoint that resolves a worktree without a project id" the
  done-when rules out (haninaguib, 2026-08-26).
- Added `ProjectRepository.createReady` alongside the existing `create` (#42) —
  the bootstrap project is already checked out, so it skips `CLONING` entirely
  rather than faking a clone that never runs (haninaguib, 2026-08-26).
- Added a second Angular route, `projects/:projectId/issues` (no `:id`), for
  "a project is picked but no issue is selected yet" — the default-project guard
  redirects there, and the original route only ever had `issues/:id` (issue
  required). Missing this made the redirect target unroutable
  (`NG04002: Cannot match any routes`), caught by the Angular test suite
  (haninaguib, 2026-08-26).
- `SidenavComponent` and `ConsoleIndicatorComponent` switched from `OnInit` to
  `OnChanges` — Angular's `@if` in `app.component.html` only tears a child down
  on a falsy transition, so switching from one project to another (both truthy)
  leaves the component mounted and never re-fires `ngOnInit`; reloading on a
  `projectId` change needed `ngOnChanges` instead (haninaguib, 2026-08-26).

## Deviations / notes
- none
