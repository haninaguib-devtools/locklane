# 738 — Sidenav does not show newly created project until manual refresh

Issue: #738

## Asked
When a user creates a new project through the "create new repo" flow, the sidenav's
project list doesn't show the new project until the page is manually refreshed (F5),
even though the existing wiring (from #717) is meant to reveal it automatically.

## Done when
- Creating a new project results in the new project appearing in the sidenav's project
  list without a manual page refresh.
- The root cause of the stale sidenav is identified and fixed, not just papered over
  with a delay/retry.

## Explicitly not
none

## Decisions made along the way
- Root cause identified (Claude, 2026-09-06): `SidenavComponent.refresh()` guards
  against overlapping reloads with a `refreshing` boolean — a second `refresh()` call
  made while one is already in flight is silently dropped, doing nothing. Every full
  reload's completion calls `maybeReveal()`, but that call gives up permanently (no
  retry) if the just-reloaded list doesn't yet contain the row being revealed
  (`revealProject`'s `pendingRevealId`). So if `revealProject()`'s own `refresh()` call
  (fired from `AppComponent.onProjectCreated` after a "create new repo" success) lands
  while an unrelated refresh is already in flight — e.g. `retryProject`,
  `confirmDeleteProject`, or the events service's own reconnect-triggered reload — that
  in-flight reload necessarily started before the new project existed, so it can never
  contain it; `maybeReveal()` then sees no matching row, gives up, and nothing else ever
  retriggers a load for that project until an unrelated future one (or a manual page
  refresh) happens to include it. This exactly matches the issue's own "an in-flight
  `list()` call ... not finding the new row yet" hypothesis, and explains why F5 (which
  runs `ngOnInit`'s own fresh load, well after creation) always "fixes" it.
- Fix (Claude, 2026-09-06): `refresh()` now queues a call that arrives while one is
  already in flight (`refreshQueued`), and re-runs itself once the in-flight one settles
  — a proper coalesced-refresh pattern, not a blind delay/retry: it guarantees every
  requested refresh eventually gets a load that started no earlier than the request
  itself, so a revealed project can never be permanently lost to a load that started too
  early to see it. `load()`'s error handler was adjusted so a failed in-flight reload
  doesn't drop the pending reveal out from under a just-queued follow-up attempt.

## Deviations / notes
- Verification: extended `sidenav.component.spec.ts` with a test that reproduces the
  exact race (a `revealProject()` call landing while another refresh is already in
  flight) and confirms the reveal now completes once the coalesced follow-up refresh
  runs; without the fix this test fails (the follow-up request never fires). Also
  updated the pre-existing `refresh() is a no-op while a refresh is already in flight`
  test, which asserted the old (buggy) drop-it behavior, to instead assert the new
  coalesced behavior — renamed to `refresh() coalesces a call that arrives while one is
  already in flight (#738)`. Ran the full client suite (`ng test`, 761 specs) and the
  sidenav spec alone (85 specs): all pass.
- Not independently verified by hand in a live browser: this sandboxed environment has
  no configured GitHub credentials/engine backend to exercise the real "create new repo"
  flow end-to-end. Verification here is by unit test against the exact race condition
  identified as the root cause; a human should confirm in a real browser session before
  or shortly after shipping.
