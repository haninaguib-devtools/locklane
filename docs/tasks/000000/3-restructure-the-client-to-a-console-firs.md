# 3 — Restructure the client to a console-first UI
Issue: #3 · Part of: #1

## Asked
Rebuild the Angular PWA client's main content area so agent consoles are the primary
surface, not issue detail. The sidenav (issue list/navigation) stays as it is today.
Reference the old locklane repo for existing xterm.js wiring patterns and the current
visual style — carry that visual style forward unchanged; this is a layout/
information-hierarchy restructure, not a new visual language. Do not copy files from
the old repo; write this repo's own implementation. Depends on the server's PTY-
session API to attach a live terminal to a worktree.

Target layout: a condensed issue header (title, number, "?" icon, one-line truncated
description), a flow-state strip (open → plan → work → review → ship), a row of
worktree tabs, and below them a live xterm.js console wired to the session API.

## Done when
- The main content area, viewing any issue, shows the condensed header + flow strip +
  worktree tabs + live console described above, instead of today's issue-detail-first
  layout.
- Clicking the "?" icon opens a popup with the task record path, checks, and branch &
  PR info; the main view has no persistent panel showing that information.
- Switching worktree tabs switches which live PTY session's terminal is shown, without
  losing either session's state.
- Visual style (surfaces, borders, label treatment, step-flow visual) is unchanged from
  the current running app.

## Explicitly not
- Changes to the sidenav/issue list beyond what was necessary to make it real
  (see Decisions) — no tree/initiative nesting, no stage badges there.
- GitHub API caching — delivered by #4.

## Decisions made along the way
- **This repo had no existing sidenav at all** (only the `engine/` backend existed
  when this task started) — the issue's Non-goal ("sidenav stays as it is today")
  assumed a sidenav this fresh repo didn't have. Paused mid-task on first discovering
  this, opened #15 and #16 for the backend data gaps it also surfaced (flow-state/PR
  data, worktree-to-issue mapping), and built a minimal but real sidenav here once
  they shipped: fetches `/api/issues`, flat list (no tree/initiative nesting, no
  stage derivation client-side beyond what #16's endpoint already returns) — enough
  navigation for the console-first area to have something to select, nothing more
  (haninaguib, 2026-08-25).
- Scaffolded `client/` as a standalone Angular 19 workspace (`ng new`), matching the
  old repo's Angular major-version-adjacent choice (it uses 20) without pinning to
  it exactly — this is a fresh, independent scaffold. `@xterm/xterm` and
  `@xterm/addon-fit` pinned to the same versions the old repo references
  (`^6.0.0`/`^0.11.0`) — reused as technical parameters, not files
  (haninaguib, 2026-08-25).
- Visual style tokens (colors, borders, mono font) authored fresh into this repo's
  own `client/src/styles.css`, matching the old repo's `styles.css` design-token
  values — the issue explicitly asks to "carry that visual style forward unchanged,"
  so reusing the token *values* (not the file) is the ask, not a workaround of it
  (haninaguib, 2026-08-25).
- `client/` is **not** wired into the Maven build or CI as part of this task — no
  `frontend-maven-plugin`, no client module added to the root `pom.xml`, no new CI
  job running `npm test`. That would touch protected files (`AGENTS.md` §Checks,
  `.github/workflows/ci.yml`) beyond this issue's client-only Scope. Flagging as a
  worthwhile follow-up, not opening it myself (haninaguib, 2026-08-25).
- Terminal input: raw xterm.js keyboard capture (`term.onData` → WebSocket), no
  separate HTML input box — the mockup's "agent>" prompt is the shell's own prompt
  text rendered inside the terminal, not client-side UI chrome
  (haninaguib, 2026-08-25).
- Worktree tabs: renders whatever `/api/issues/{number}/worktrees` (#15) reports,
  including zero. Deliberately does **not** auto-create a "main" worktree tab with a
  guessed working directory when none exist yet — the client has no reliable way to
  know what directory a brand-new session should start in, and guessing wrong would
  be worse than an honest empty state ("no worktree sessions yet for this issue").
  Starting a new agent session from the client is a real gap worth its own follow-up
  (haninaguib, 2026-08-25).
- Flow-state strip's "current" stage: the first not-done step, or the last step once
  everything is done (`FlowStripComponent.currentIndex()`) — matches the mockup's
  single filled-dot-on-current-stage behavior (haninaguib, 2026-08-25).

## Deviations / notes
- `ng build` succeeds but warns the initial bundle (536.73 kB) exceeds the default
  500 kB budget in `angular.json` — almost entirely xterm.js's own size. Not adjusted;
  a non-blocking warning, flagged rather than silently tuned.
- Manually verified the full stack end to end in a real browser (backend + Angular
  dev server, real GitHub data, a real inserted worktree-session row, a real spawned
  shell): sidenav loads real issues; header/flow-strip/popup show correct data for
  both an in-progress issue (this one) and a shipped one (#16, all steps checked,
  real record path/checks/branch/PR); worktree tabs show the correct empty state and
  a real tab once one exists; the terminal connects, replays buffered output on
  reattach (reload the page, reselect the issue and tab — the earlier session's
  output is still there), and executes real commands. One caveat: the browser
  automation tool's synthetic Return keypress did not reach xterm's own key handler
  (a tool quirk, not a bug in this code) — confirmed the full round-trip instead by
  sending `\r` directly through the session object via the browser's JS console,
  which executed correctly and produced real shell output.
