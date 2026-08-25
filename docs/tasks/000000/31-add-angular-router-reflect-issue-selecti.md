# 31 — Add Angular Router, reflect issue selection in the URL
Issue: #31

## Asked
The client has no Angular Router: which issue is selected lives only in the
in-memory `selectedIssue` field on `AppComponent`, so it is never in the URL —
closing and reopening the app, or sharing a link to a specific issue, doesn't
work. Wire in the Router so an issue's page lives at `/issues/:id`, and is a
prerequisite for a follow-up "jump to my open consoles" indicator that needs
somewhere real to navigate to.

## Done when
- Angular Router is wired into the app (`provideRouter`).
- Selecting an issue navigates to `/issues/:id`; loading `/issues/:id` directly
  selects that issue.
- The manual `selectedIssue` field is replaced by reading the route param.
- Client tests pass.

## Explicitly not
- Console-tab state in the URL — stays in browser-local storage (#30); see the
  follow-up issue for the header console indicator.

## Decisions made along the way
- Used a **component-less route** (`{ path: 'issues/:id' }`, no `component`) and
  no `<router-outlet>`, rather than routing to a wrapper component. `AppComponent`
  is the whole app shell (sidenav + main content are always siblings, never
  swapped by route), and the sidenav's active-issue highlight needs the same
  value `app-main-content` gets — so the natural owner of "what issue does the
  URL say" is `AppComponent` itself, not a routed child it would have to hand
  the value back up to. `AppComponent` derives it by walking
  `ActivatedRoute.snapshot.firstChild` on every `NavigationEnd`; the Router
  builds that match tree independent of whether anything renders it via an
  outlet, so this needs no outlet to work. (Claude, 2026-08-25)
- `select()` on `AppComponent` now calls `Router.navigate(['/issues', id])`
  instead of assigning a field — the URL is the single source of truth
  (`selectedIssue` is a signal derived from it, not writable). (Claude,
  2026-08-25)

## Deviations / notes
- Added `engine/src/main/java/dev/locklane/engine/SpaFallbackController.java` and
  its test, outside the issue's declared Scope (`client/src/app/`). Manual
  browser verification of the done-when's second bullet ("loading `/issues/:id`
  directly selects that issue") showed a real 404: the engine had no
  server-side mapping for `/issues/31`, so a direct load or reload never
  reached the Angular app at all. This isn't a discovered-but-unrelated
  defect — it's this task's own explicit acceptance criterion failing to
  hold — so, following the precedent in #29's record for necessary plumbing
  a scoped change cannot function without, added a controller forwarding
  `GET /issues/{id}` to `index.html`. Kept to that one exact route (not a
  general multi-segment SPA catch-all) since it's the only client-side route
  that exists today; the root path already worked via Boot's welcome-page
  handling of `index.html`.
