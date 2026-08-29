# 296 — Add OpenCode as a supported terminal agent (client)
Issue: #296 · Part of: #294

## Asked
Let users pick OpenCode as their terminal agent in the Angular client, the same way they
can already pick Claude Code or Codex — in the agent picker, the default-agent setting,
and the usage widget. "claude" and "codex" are each hardcoded independently as a
`'claude' | 'codex' | 'shell'` union and matching fixed fields (no shared abstraction);
this task extends that same pattern to a third value, `'opencode'`.

## Done when
- The `Agent` type in `agent-store.ts` includes `'opencode'` alongside
  `'claude' | 'codex' | 'shell'`, with its validity check updated to accept it.
- The agent picker component offers OpenCode as a selectable choice.
- The default-agent setting (`default-agent-store.ts` / settings dialog) supports
  choosing OpenCode as the default agent.
- The usage model and usage widget display OpenCode usage alongside Claude/Codex, once
  the server's usage API returns it.
- Existing Claude/Codex/shell behavior is unchanged.
- The client test suite passes.

## Explicitly not
- Any engine/server change — done in #295.
- Redesigning the usage widget's layout beyond accommodating a third provider.

## Decisions made along the way
- `console-tabs.component.ts` and `sidenav.component.ts` (named in the issue's Scope)
  needed no edits: both consume `Agent`/`DefaultAgent` generically (typed input, or a
  value read straight from `DefaultAgentStore.agent()`) with no exhaustive claude/codex
  switch, so extending the two type unions to include `'opencode'` flows through them
  unchanged. (Hani, 2026-08-28)
- The usage widget's third provider color (`.mini-bar.opencode` /
  `.usage-window-bar.opencode`) is a literal hex scoped to that component's own CSS file,
  not a new global design token — `client/src/styles.css` (where `--green`/`--amber`
  live) is outside this task's Scope. (Hani, 2026-08-28)

## Deviations / notes
- Adding the required `opencode: ProviderUsage` field to `UsageSnapshot`
  (`usage.model.ts`, in Scope) breaks TS compilation of three `UsageSnapshot` object
  literals outside the issue's named Scope: `services/usage.service.spec.ts`,
  `components/sidenav/sidenav.component.spec.ts`, `app.component.spec.ts`. Added the
  matching `opencode: { available: false, ... }` fixture field to each — the minimal,
  mechanical fallout of the one Scope-sanctioned model change, needed to keep "the client
  test suite passes" true, not a functional or drive-by change. (Hani, 2026-08-28)
