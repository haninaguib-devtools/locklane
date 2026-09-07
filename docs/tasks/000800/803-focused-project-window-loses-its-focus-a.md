# 803 — Focused project window loses its focus after in-app navigation
Issue: #803

## Asked
A project popped out into its own browser window (the sidenav's pop-out control,
#286) should stay narrowed to that one project for as long as the window is open:
clicking an issue row, opening a console, using back/forward, pressing the sidenav's
refresh button, reloading the page, or copying the URL must all keep the window
focused. Today the first in-app navigation drops the `focus=1` query parameter the
window relies on, and the loss surfaces later — typically when the sidenav's refresh
rebuilds the list and every project appears. The URL stays the single source of truth:
every in-app navigation inside a focused window carries `focus=1` forward, and the
sidenav reacts the moment its `focusedProjectId` input changes rather than waiting for
the next refresh.

## Done when
- In a popped-out focused window, after clicking an issue row, opening a console via
  the sidenav's new-console button, and navigating back, the address bar still carries
  `focus=1` (human check in the browser).
- In that same window, clicking the sidenav's refresh button shows only the focused
  project's section; every other project stays absent (human check).
- After those navigations, a full page reload (F5) reopens the window still focused on
  the one project (human check).
- The `new` query parameter is still dropped from the URL after the console page
  consumes it, and `focus=1` survives that drop (unit test).
- A unit test in `sidenav.component.spec.ts` proves that a change of the
  `focusedProjectId` input from a project id to `null`, and from `null` to an id,
  re-narrows the rendered sections without waiting for `refresh()`.
- A unit test proves that an in-app navigation started from a URL carrying `focus=1`
  lands on a URL that still carries it.
- An ordinary, non-focused window is unchanged: no `focus` parameter ever appears in
  its URLs; `cd client && npm test` is green.
- `./mvnw -B test` from the repo root passes.

## Explicitly not
- No change to how a focused window is opened (the pop-out control and `window.open`
  stay as they are).
- No persistence of focus outside the URL (no session storage, no service state): the
  URL remains the only carrier, by design of #286.
- No change to the engine; this is client-only.

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- **A focus-only carry, not Angular's router-wide `defaultQueryParamsHandling:
  'merge'`** (agent, 2026-09-07). The issue named the router-wide default as the
  expected shape unless it leaked unrelated parameters; it does. Every other query
  parameter the client uses is a one-shot handoff between two specific pages, and the
  console page deliberately drops each from the URL once it has acted on it —
  `clearNewParam()` for `new` (#370), `dropQueryParams('dir', 'resume', 'tool')` for
  the reopen handoff (#752, #795) — by navigating without it. A `merge` default
  defeats exactly that: it puts the current URL's copy straight back, so a reload
  would mint another console or relaunch a resume, undoing both fixes; `session`
  would likewise follow the user onto an issue page and back. Only `focus` is a
  property of the window itself.
- **The carry lives in the Router itself** (agent, 2026-09-07): `FocusPreservingRouter`
  extends Angular's `Router`, overriding `createUrlTree` — the one method every
  `routerLink` (its rendered `href` and its click) and every `router.navigate()` build
  their URL through — to add `focus=1` when the URL the window is showing carries it
  and the caller did not set `focus` itself (`focus: null` still drops it).
  `app.config.ts` provides it in place of the stock `Router` (`provideRouter` does not
  provide that token itself, so the override is the whole wiring). Alternatives
  rejected: a per-call helper at each of the ~12 navigation sites would have reached
  well outside the task's Scope (overview, project summary, console indicator,
  add-project popup) and is exactly the kind of thing the next navigation site forgets;
  a redirecting route guard would have cancelled and re-issued every navigation and
  left rendered `href`s without `focus`, so a copied or middle-clicked link would lose
  it.
- **`FocusPreservingRouter` and `isFocusedRoute()` sit in
  `current-project.service.ts`** (agent, 2026-09-07), the file that already owns the
  reading of `focus=1` (`isFocusMode()`, #449), rather than a new file the Scope does
  not name: one file now both reads focus and keeps it, from one shared predicate.
- **The sidenav reloads on a `focusedProjectId` change** (agent, 2026-09-07) via
  `ngOnChanges` → the same `load()` an events-channel reconnect runs (not `refresh()`,
  which spins the button and queues), guarded by an `initialized` flag rather than
  `SimpleChange.firstChange`: the value bound before `ngOnInit` is read by its own
  load, and `firstChange` only says whether the input system saw an earlier value —
  `ComponentRef.setInput` on a never-bound input reports `firstChange: true` after
  init, which is what the spec exercises. Narrowing carries the focused project's
  already-loaded tree over and asks for no other project's tree.
- **No `CHANGELOG.md` edit** (agent, 2026-09-07): the Scope lists its unreleased
  section, but the file has no such section — release notes are generated from squash
  commits at cut time (`docs/architecture/releasing.md` § Release notes), and no task
  merged since v0.2.20 touched it. Nothing to write here.

## Deviations / notes
- The "`new` dropped, `focus=1` survives" combination was already covered by an
  existing spec (`project-console.component.spec.ts`, "drops ?new once it has been
  acted on … (#370)", which navigates from `?new=1&focus=1`); it keeps passing
  unchanged and `project-console.component.ts` needed no edit.
- The service spec now runs every existing test under `FocusPreservingRouter` too, so
  the pre-existing "ordinary window" cases double as the proof that an unfocused
  window never gains `focus`.
- Three of the Done-when items are browser checks (address bar after navigation,
  refresh in a focused window, F5) that a unit test cannot stand in for; they remain
  for a human, without a `## Verification` entry since the task has no plan declaring
  one.
