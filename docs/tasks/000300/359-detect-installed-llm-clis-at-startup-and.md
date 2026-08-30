# 359 — Detect installed LLM CLIs at startup and hide unavailable options in Settings
Issue: #359

## Asked
When the server starts up, it should check which LLM command-line tools are actually
installed on the host machine, and the Settings dialog's "Default agent" picker should
only offer the ones that are actually available — instead of always showing all three
regardless of what's installed. Previously the picker always rendered buttons for all
three supported CLIs even when only some were actually installed on the server, letting
someone pick a default agent that would fail to launch a session.

## Done when
- On startup, the engine probes the host `PATH` for each of the three currently-supported
  CLI binaries — `claude`, `codex`, `opencode` — and records which are present.
- The client can learn which agents were detected as installed, and the Settings dialog's
  "Default agent" picker renders a button only for an agent detected as installed.
- If the currently-saved default-agent preference points at a CLI no longer detected as
  installed, the dialog still renders without error.
- `./mvnw -B test` passes.
- Manual check (human-judged): with only a subset of the three CLIs on the server's
  `PATH`, restarting the server and opening Settings shows only the buttons for the CLIs
  that are actually installed.

## Explicitly not
- Supporting CLI providers beyond the current three (`claude`, `codex`, `opencode`).
- Re-probing after startup — detection runs once at boot.
- Changing the per-session `Agent` type in `client/src/app/services/agent-store.ts` (which
  also includes the `shell` pseudo-agent).
- Changing how a launched session actually invokes the chosen CLI
  (`TerminalWebSocketHandler`'s launch/resume logic).

## Decisions made along the way
- Detection scans `PATH` directories directly for an executable file named after each
  CLI, rather than spawning a process per candidate (e.g. `which`) — cheaper and
  deterministic, and avoids depending on a `which`/`command` binary being present in
  the runtime environment. (/t-drive session, 2026-08-29)
- The new `GET /api/agents/installed` endpoint follows the existing `UsageController`
  pattern (own package, own store/service, plain `@RestController`) and is gated behind
  authentication in `SecurityConfig` the same way `/api/usage` is — same
  account-scoped reasoning. (/t-drive session, 2026-08-29)
- The client's `DefaultAgentStore` fetches the installed set lazily via a
  `refreshInstalled()` method called from the Settings dialog's `ngOnInit`, rather than
  eagerly in the store's constructor — the store is also injected from the sidenav (to
  launch a console with the preferred agent), and an eager fetch there fired an
  unexpected HTTP request in every test that injects the store, unrelated to the
  Settings dialog. The fetch runs at most once per app load (a no-op on a later call
  once already requested, retried on failure). (/t-drive session, 2026-08-29)
- `installed` defaults to all three agents until the fetch resolves (or if it fails) so
  the picker never renders empty; this also covers the "saved preference points at a
  CLI no longer installed" done-when item — the picker simply doesn't mark any option
  `chosen`, no special-cased fallback needed. (/t-drive session, 2026-08-29)

## Deviations / notes
- Touched one file outside the issue's literal Scope list:
  `client/src/app/app.component.spec.ts`. Adding the new `/api/agents/installed` fetch
  to `DefaultAgentStore` (an in-scope file) broke an existing test in that spec file
  that opens the Settings dialog without expecting the new request; the one-line fix
  (expect and flush it) is a required consequence of the in-scope change, not new
  behavior of its own, so it is included here rather than proposed as a separate issue.
