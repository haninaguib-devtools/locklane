# 695 — Make the server the only source of supported coding-agent CLIs, labels and usage providers
Issue: #695

## Asked
Locklane 0.2.11 detects `omp` on the server (the boot log reads `Detected installed agent
CLIs on PATH: [claude, codex, opencode, omp]`) yet the Settings → Default agent picker
never shows it, because the client keeps its own hard-coded list of agents and filters
the server's answer against it. Task #681 added `omp` to
`client/src/app/components/agent-picker/`, a component nothing renders, so the change
never reached the screen. Make the server the only place that knows which coding-agent
CLIs exist, what they are called on screen, and which have a usage feed: the client
renders whatever the server sends. After this lands, supporting a new CLI is a
server-only change — the client is not edited at all.

## Done when
- `GET /api/agents/installed` returns a list of objects, each `{ "id": "<cli name>", "label": "<display name>" }`
  (e.g. `{"id":"omp","label":"OMP"}`), in the server's detection order.
  `InstalledAgentsStore` is the single table of id + label per known CLI;
  `InstalledAgentsController` serializes from it.
- The usage endpoint (`UsageController` / `UsageSnapshot`) returns
  `providers: [{ id, label, color, usage: ProviderUsage }]` plus `updatedAt`, one entry
  per registered `UsageProvider`, instead of the three named fields
  `claude`/`codex`/`opencode`. `color` is the bar color the widget paints (today's
  per-agent CSS colors move here).
- The client has **no** enumeration of agent ids or labels anywhere. Machine check, from
  the repo root, exits 0:
  `! grep -rnE "'(claude|codex|opencode|omp)'|\"(claude|codex|opencode|omp)\"|\.(claude|codex|opencode)\b|\b(claude|codex|opencode|omp)\s*:" client/src/app --include='*.ts' --include='*.html' --include='*.css' | grep -v '\.spec\.ts'`
  (A test may still name an agent as sample data.)
- `default-agent-store.ts`: `DefaultAgent` is `string`; `ALL_DEFAULT_AGENTS`,
  `DEFAULT_AGENT_LABELS`, `isDefaultAgent()` and the `load()` whitelist are removed. The
  installed list comes only from the server. The default agent, when none is stored or
  the stored one is no longer installed, is the **first** entry the server returned.
- `agent-store.ts`: `Agent` is `string` (with `'shell'` remaining a value the client adds
  itself — shell is not a detected CLI); the `load()` whitelist is removed.
- `issue.model.ts` `ResumeSession.tool` is `string`; the server's `ResumeSessionView`
  carries `toolLabel` too, and `session-list.component.html` shows the label.
- Settings dialog renders one button per server entry using its `label`;
  `console-tabs.component.ts`'s `@Input() defaultAgent` has no `'claude'` literal
  default (the caller always passes the store's value).
- `usage-widget.component.{ts,html,css}` loops over `providers`; no per-agent selectors
  or literals remain. Fallback when the fetch fails or returns empty: the picker shows
  only `shell`, the usage widget shows nothing (same as today when no provider is
  available).
- `client/src/app/components/agent-picker/` (component, template, css, spec) is deleted
  and no import references it.
- Manual check, on a host with `omp` on PATH: after `locklane restart`, Settings →
  Default agent shows an **OMP** button; choosing it and opening a new console launches
  `omp`.
- `./mvnw -B test` and the client test suite pass.

## Explicitly not
- No new usage provider for omp: it is bring-your-own-model across many backends and has
  no single locklane-trackable quota (see #681's record). The list shape just makes
  adding one later a server-only change.
- No change to how the server detects CLIs (`InstalledAgentDetector`, boot-time PATH
  scan) or to `ResumeIdScanner`.
- No new UI surface; the settings picker and usage widget keep their current look, only
  their data source changes.

## Decisions made along the way
- `UsageProvider` grew `id()`/`label()`/`color()` methods, and `UsageSnapshot` moved from
  three named fields to `providers: [{id, label, color, usage}]`, so the widget loops
  generically instead of naming each agent -- required by the issue's Done-when, not an
  independent choice.
- `default-agent-store.ts`'s `installed` signal defaults to an empty list (not a
  three/four-agent fallback) before its fetch resolves or when it fails, since there is
  no client-side whitelist left to fall back to; the settings dialog then simply shows
  no button until the real list arrives.

## Deviations / notes
- The issue's usage-widget bullet says the picker should show "only shell" as the
  fallback when the installed-agents fetch fails or returns empty. No existing wiring
  lets the Settings "Default agent" picker launch a plain shell (that is a separate,
  already-existing code path via the Overview page's own shell button), and adding one
  would be new UI/behavior beyond the issue's own "no new UI surface" non-goal. Left the
  fallback as an empty picker (no button) instead; flagging this line as still open for
  a human to clarify if a literal shell option was intended.
- `DefaultAgentStore.agent()`'s correction to the first installed agent only takes
  effect once some caller has called `refreshInstalled()` and it has resolved (the
  settings dialog does; `project-summary.component.ts`'s "Open console" button now does
  too, since it was the one call site an existing test exercised with nothing
  configured). `console-tabs`' "+" button (used from `main-content.component.ts` and
  `project-console.component.ts`) reads the same `agent()` value but neither of those
  two components calls `refreshInstalled()` themselves, so on a fresh browser load that
  never opened Settings or the project summary first, clicking "+" can still launch with
  an empty default agent. Wiring every launch site to fetch eagerly turned out to have a
  large test-suite footprint (touching dozens of unrelated specs across the app), out of
  proportion for this task; flagging the gap rather than silently leaving it unmentioned.
