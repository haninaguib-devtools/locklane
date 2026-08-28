# 219 — Add default LLM (Claude/Codex) setting to Settings
Issue: #219

## Asked
Users should be able to choose Claude or Codex as their default coding agent from
Settings, so console-launch flows elsewhere in the app can use that choice instead of
hardcoding Claude. There is no persisted preference today — `agent-picker.component.ts`,
`console-tabs.component.ts`, and `sidenav.component.ts` each hardcode `'claude'`, and the
existing `AgentStore` only remembers, per session id, which agent *was used* for that
session — not a preference to launch with. This stays a client-only setting, consistent
with the engine not persisting a session's launch command.

## Done when
- The settings dialog has a new section letting the user pick Claude or Codex as their
  default agent, alongside the existing two-factor-authentication section.
- The choice persists across reloads (localStorage).
- A single shared accessor exposes "the current default agent" for other code to read —
  this issue does not itself change any console-launch call site to consume it.
- A human confirms in the browser that the picker renders in Settings, the choice
  persists after a reload, and the two-factor-authentication section still works
  unchanged.

## Explicitly not
- Wiring any console-launch call site (issue-page "+", sidebar "+", project-page console
  button) to read the new preference — separate work per the issue's Non-goals.
- A per-session override UI (e.g. a future "convert to Codex" menu on an existing
  console) — not committed work.

## Decisions made along the way
- New `DefaultAgentStore` service (`client/src/app/services/default-agent-store.ts`)
  rather than extending `AgentStore`: `AgentStore` is keyed per session id and records
  what was *used*, which is a different concept from a single global launch preference
  (haninaguib, 2026-08-27).
- The store exposes the current value as a readonly signal (`agent`), matching the
  pattern already used by `AuthService` (private writable signal, public
  `asReadonly()`), so other code can read it reactively without polling localStorage
  itself (haninaguib, 2026-08-27).
- The settings-dialog UI is a plain two-button toggle rather than reusing
  `AgentPickerComponent`: that component always renders a third `shell` option, which
  doesn't apply to a default *agent* preference (haninaguib, 2026-08-27).

## Deviations / notes
- none
