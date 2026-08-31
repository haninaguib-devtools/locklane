# 446 — Add the Shells window (sidenav + terminal, singleton popup)
Issue: #446 · Part of: #444

## Asked
A singleton browser window — opened via `window.open(url, 'locklane-shells')` so
repeated triggers focus the same window instead of stacking duplicates — with its
own sidenav grouping every open shell by project (and, within a project, by issue
or the project's own main checkout), and a content area showing the xterm.js
terminal for whichever shell is selected. It exists so ad-hoc shells (tailing
logs, running a one-off command) don't clutter the existing console tab strips,
and it works whether the engine is local or remote, since it attaches to shell
sessions over the existing WebSocket terminal pipeline.

## Done when
- A new route (`/shells` and `/shells/:id`) renders a minimal shell — no
  topbar/sidebar from the main app — with a sidenav on one side and a terminal on
  the other.
- The sidenav lists every open shell from the listing endpoint added in #445,
  grouped by project, and within a project by issue number or "Main"; each row's
  label follows the existing tab-label convention (location + index-from-second,
  e.g. `Main`, `Main 2`, `#438`, `#438 · wtree 2`) with no agent suffix.
- Selecting a row navigates to `/shells/:id` and renders that shell's terminal in
  the content area, reusing the existing terminal component.
- The sidenav updates live when a shell is opened or closed elsewhere, by
  subscribing to the existing `consolesChanged` event on `/ws/events`.
- Closing a shell from this window behaves like closing a console tab elsewhere
  (same confirmation dialog), and does not close the window itself even when it
  was the last shell.
- A client test covers: shells render grouped and labeled correctly from a given
  listing response; selecting a row shows that shell's terminal; a
  `consolesChanged` event adds a new row without a manual reload.
- `./mvnw -B test` and `./.t-workflow/scripts/consistency-check.sh` pass.

## Explicitly not
- The two buttons that create a new shell and open/focus this window — #447 and
  #448; this task only needs to work correctly given shells that already exist.
- Renaming a shell from within this window beyond whatever the existing
  tab-rename affordance already provides — no new rename UI here.

## Decisions made along the way
- The routes are component-less (`children: []`) and `AppComponent` branches on
  the leading `shells` segment to render `ShellsWindowComponent` instead of the
  authed shell (agent, 2026-08-31): this app deliberately has no router-outlet —
  `AppComponent` is the whole shell and reads the route directly (see
  `app.routes.ts`'s own comment) — so a shells "page" is a new branch there, the
  same way the project-console route is detected, still behind the login check.
- Closing a shell calls #460's `DELETE /api/projects/{projectId}/shells/{id}`
  through a new `ShellsService`, behind the existing `ConfirmDialogComponent`
  (agent, 2026-08-31) — the same dialog console tabs use, parameterized the same
  way; on success the list is re-fetched and, if the closed shell was selected,
  the window navigates to bare `/shells` — never closes itself.
- Live updates subscribe to `EventsService.events$` filtered by
  `isConsolesChangedEvent`, plus `reconnected$` for catch-up after a dropped
  socket (agent, 2026-08-31) — the shell close/open broadcasts ride the existing
  `consolesChanged` event (#445/#460 pinned that server-side), so no new event
  type exists.
- Row labels: first shell at a location is `Main` / `#<issue>`; from the second
  on, `Main 2` / `#<issue> · wtree 2` (agent, 2026-08-31) — exactly the issue's
  own examples, computed by a `labelShells` helper next to the sidenav component,
  mirroring `labelConsoles`' seen-counter shape; a user-given `displayName`
  (#393) shows in place of the auto label, same as every tab strip.
- Every listed shell's terminal stays mounted, hidden with CSS when not selected
  (agent, 2026-08-31) — the same keep-alive pattern `project-console` and
  `main-content` use (#30), so switching shells never drops a connection or its
  scrollback; terminals attach with `cmd=shell` and the listing's
  `workingDirectory` as `dir`.

## Deviations / notes
- none
