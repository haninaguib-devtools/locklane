# 979 — Add a per-user Remote Control toggle for Claude sessions
Issue: #979

## Asked
Add a per-user "Remote Control" toggle to the Settings dialog. When on, a brand-new
Claude session Locklane launches is started with Claude Code's own `--remote-control`
flag, so it shows up in the claude.ai / Claude mobile app session list. When off,
launch is exactly as today. This is Claude-Code-specific: it has no effect on codex,
opencode, omp, or muse sessions, and no effect on resuming or reattaching to an
already-running session (only the initial launch of a new one takes a `--remote-control`
flag).

Follow the existing "Default agent" preference as the shape to match: a client-only
preference, persisted in `localStorage` (see `DefaultAgentStore`, `#219`), added to the
Settings dialog as a checkbox toggle (see the "Notifications" section's
`notification-toggle` for the pattern). The client already carries a session's launch
choice to the engine as a WebSocket query parameter on connect
(`client/src/app/services/terminal-session.ts`'s `cmd`/`resume`/`seed`, read server-side
by `TerminalWebSocketHandler.queryParam`); add one more such parameter for this toggle,
sent only when the session's agent is `claude`. Server-side, thread it into
`TerminalWebSocketHandler.resolveLaunch`/`resolveLaunchCommand`/`seededLaunchCommand` so
a brand-new `claude` launch (positional-prompt or bare) gets `--remote-control` inserted
into its argv, ahead of the existing `--settings <bell-hooks-json>`/`--resume <id>`
handling those methods already do. A reattach to an already-running PTY takes no launch
parameters at all today (the engine ignores them once a process exists) and this must
not change that.

## Done when
- The Settings dialog has a "Remote Control" toggle, off by default, whose state
  persists in `localStorage` across reloads (same durability as the existing "Default
  agent" and "Notify me when an agent is waiting" settings).
- With the toggle on, opening a brand-new Claude agent session (not a reattach, not a
  resume) launches the `claude` CLI with `--remote-control` in its argv; a unit test on
  `TerminalWebSocketHandler`'s launch-command resolution covers this.
- With the toggle off (including its default state), the `claude` launch argv is
  byte-for-byte what it is today.
- The toggle has no effect on codex/opencode/omp/muse launches, and no effect on
  `--resume`/reattach argv construction beyond `--remote-control` being present or
  absent per the toggle.

## Explicitly not
- No engine-side/server-side persistence of the preference — client-only, like the
  existing default-agent and notification preferences.
- No change to what a reattach or a `--resume` launch does beyond carrying the same
  flag through when applicable; no new UI surfacing of whether Remote Control is
  actually connected (that status lives in Claude Code's own `/remote-control` UI and
  claude.ai, per the engine's own vocabulary in ADR-112 — Locklane's own tab chrome is
  unaffected).
- No support for other agent CLIs' equivalent remote-session features, if any.

## Decisions made along the way
- `RemoteControlStore` (`client/src/app/services/remote-control-store.ts`) is a plain
  boolean preference, the same shape as `NotificationsStore` minus its permission
  logic — simpler than `DefaultAgentStore` since there is no server-side "installed"
  set to reconcile against.
- `terminal.component.ts` injects `RemoteControlStore` directly rather than adding a
  new `@Input`: it already receives `cmd` (the launch agent), so it can decide
  `cmd === 'claude' && remoteControlStore.enabled()` itself without any change to
  `main-content`/`project-agent-session`'s templates, which stayed out of scope.
- Engine-side, `resolveLaunch`/`resolveLaunchCommand`/`seededLaunchCommand` each got a
  new overload taking `remoteControl` (default `false` on the existing signature), so
  every other call site — including the three other test files that exercise these
  methods — is untouched. `withRemoteControl` inserts `--remote-control` right after
  `"claude"`, ahead of `--resume <id>`/`--settings <json>`, then the existing
  `withClaudeBellHooks` wrapping runs unchanged.
- The engine reads `remoteControl` unconditionally from the query string
  (`"true".equals(...)`) rather than special-casing it to `cmd == "claude"`
  server-side too: `withRemoteControl` is only ever invoked from the `cmd.equals("claude")`
  branches, so a stray param for another tool is already a no-op.

## Deviations / notes
- none

## Agents
- work: claude-code / claude-sonnet-5
