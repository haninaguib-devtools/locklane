# 197 — Add an overview landing page at the root URL
Issue: #197

## Asked
Visiting Locklane at `/` silently redirects into whichever project happens to be
first, with no way to see the whole workspace at a glance. Add a real "Overview"
landing page: aggregate stat tiles (projects, total/open/closed issues,
initiatives, tasks) across every project, plus a per-project breakdown showing
status and issue totals, each linking through to that project's issues page. It
replaces today's auto-redirect for anyone logged in with at least one project;
not logged in, or logged in with zero projects, keeps behaving exactly as today.

## Done when
- Visiting `/` while logged in with at least one project renders the new
  Overview page instead of redirecting into the first project.
- The page shows aggregate stat tiles computed by combining every project's own
  issue tree, reusing `project-summary.component.ts`'s `countIssues`.
- The page lists every project with its status, issue totals, and a
  closed/total completion indicator; clicking a `READY` project navigates to
  its issues page.
- The sidenav gets a persistent entry above the project list to return to the
  Overview page.
- Not logged in, or logged in with zero projects, behaves exactly as today.
- `./mvnw -B test` passes, including new client-side unit tests for the
  aggregation logic and for `/` no longer redirecting when projects exist.

## Explicitly not
- No new backend aggregate endpoint — totals are composed client-side.
- No historical/trend data — a point-in-time snapshot only.
- No filtering/customization of the overview.

## Decisions made along the way
- `defaultProjectRedirect` (#43) is removed rather than kept alongside the new
  page: its whole job was picking a project to redirect into, and the issue
  asks for that redirect to stop happening for anyone with at least one
  project. The zero-projects/not-logged-in cases it also handled need no
  redirect either — they already just let `''` activate and rendered
  AppComponent's existing empty/login states, which the new `OverviewComponent`
  reproduces for the zero-projects case (haninaguib, 2026-08-27).
- `OverviewComponent` owns its own "no projects yet" fallback (matching
  `select a project to begin`) rather than AppComponent deciding between it and
  the Overview page, so AppComponent keeps its existing rule of deriving
  everything from route params alone and never fetching project data itself
  (haninaguib, 2026-08-27).
- Per-project navigation uses a plain `routerLink` inside `OverviewComponent`
  (disabled via `[routerLink]="null"` for a non-READY project), the same
  pattern `project-summary.component.html`'s `consoles-link` already uses,
  rather than an output event routed back through AppComponent
  (haninaguib, 2026-08-27).

## Deviations / notes
- `app.routes.spec.ts` tested `defaultProjectRedirect` exclusively; deleted
  along with the function. Its "not logged in" / "zero projects" coverage now
  lives in `app.component.spec.ts` and `overview.component.spec.ts`.
- `app.component.spec.ts`'s `navigateToDefaultProject()` helper relied on the
  now-removed guard's redirect to reach the "project selected, no issue"
  state; replaced with direct navigation to `/projects/1/issues` (renamed
  `navigateToProjectSummary()`), the same pattern the file's other direct-load
  tests already used.
