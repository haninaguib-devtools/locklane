# 32 — Header indicator for open consoles, with per-issue tab memory
Issue: #32

## Asked
With multiple console tabs possible per issue (#30) and issue pages living at
real URLs (#31), show at a glance that consoles are open somewhere in the app,
and let someone jump straight to any of them from wherever they currently are
— a header badge/button opening a picker that lists live sessions and
navigates to one on click (the "Open Shells" pattern from the portstow
project). Which console tab was last active for a given issue is remembered
per-browser (`localStorage`), not baked into the URL — a personal, ephemeral
choice. If the remembered tab no longer exists, fall back to the first
still-open tab. Both the console tab bar and the picker update this stored
value when the user switches tabs.

## Done when
- A header element shows a count of currently open consoles across all
  issues.
- Clicking it opens a picker listing every open console (issue + location +
  agent); clicking an entry navigates to that issue's page with that
  console's tab active.
- The active tab per issue is persisted in `localStorage`, restored on load,
  and falls back gracefully when the stored tab is gone.
- Client tests pass.

## Explicitly not
- Nothing named as out of scope in the issue.

## Decisions made along the way
- **Necessary plumbing outside the declared `client/src/app/` scope:** a
  count/picker "across all issues" needs a list of every open console the
  caller may see, and the engine only ever exposed that per-issue
  (`GET /api/issues/{n}/worktrees`). Added `GET /api/consoles`
  (`ConsolesController`, backed by a new `IssueWorktreeService.allWorktreeIds`
  sharing the existing per-record ownership-visibility check) returning the
  same flat `List<String>` of session ids, filtered to ids that actually carry
  an issue-number prefix (a bare `"main"` or non-conforming id belongs to no
  issue and the picker has nowhere to navigate it to). Gated behind auth in
  `SecurityConfig`, same as the per-issue endpoint. This is the same kind of
  "the scoped change cannot function without it" plumbing #29 and #31 each
  needed once. (Claude, 2026-08-25)
- The picker reuses `console-labels.ts`'s `labelConsoles`/`isMainSession`
  (from #30) for each entry's `location [· agent]` label, called one session
  at a time — with an array of length 1 its "second console of this location"
  index-numbering never fires, which is what's wanted here: the numbering
  only made sense distinguishing multiple tabs *within one issue's own* tab
  bar, not across issues. (Claude, 2026-08-25)
- New `ActiveConsoleStore` (localStorage, keyed by issue number →
  session id) is the single place both the console tab bar
  (`MainContentComponent.selectConsole`/`openConsole`) and the picker
  (`jumpTo`) write the "last active tab" — `MainContentComponent` reads it on
  load and falls back to the first console when the remembered id is not in
  the current list (console closed, or never seen on this browser).
  (Claude, 2026-08-25)
- The picker re-fetches `/api/consoles` each time it opens rather than
  polling continuously — simplest thing that keeps the badge count itself
  fresh on every navigation/reload and the picker's contents fresh at the
  moment someone actually looks at it, without adding a polling loop the
  issue never asked for. (Claude, 2026-08-25)

## Deviations / notes
- See "Necessary plumbing" above.
