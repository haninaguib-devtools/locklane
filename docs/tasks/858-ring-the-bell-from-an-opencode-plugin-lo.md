# 858 — Ring the bell from an OpenCode plugin Locklane places in the user's plugin directory
Issue: #858 · Part of: #853

## Asked
Have OpenCode ring the terminal bell itself when it stops for the user. OpenCode's plugins receive a `session.idle` event when a turn ends and a `permission.asked` event when an approval is pending, which together cover every stop. Unlike the other three agents, OpenCode has no launch-time way to load a local plugin file: local plugins are read only from the user's global plugin directory (`~/.config/opencode/plugins/`, or under `OPENCODE_CONFIG_DIR` when set) and from a per-project `.opencode/plugins/`, and the launch-time config list takes npm package names only. So Locklane places exactly one file, `locklane-bell.js`, in the user's global plugin directory before the first `opencode` launch, and keeps it current: the file carries a header naming Locklane and a content hash, the engine overwrites only a file carrying that header, never a file the user wrote, and it never touches anything else in that directory. The plugin writes a bell to the controlling terminal on both events.

## Done when
- Before an `opencode` tab launches, the plugin file exists in the user's global OpenCode plugin directory with the Locklane header; an engine test covers creation, idempotence, refusal to overwrite a foreign file of the same name, and honouring `OPENCODE_CONFIG_DIR`.
- In a Locklane agent tab, an OpenCode turn ending makes the amber dot appear at that instant, and a pending approval rings too.
- `./mvnw -B test` passes.

## Explicitly not
- Publishing an npm plugin and naming it through OpenCode's inline config; that adds a network fetch at startup and a package to maintain.
- Writing into any project's `.opencode/` directory.
- Any client change.

## Decisions made along the way
- The plugin API and its two relevant hooks were confirmed against the real,
  officially published `@opencode-ai/plugin` npm package (v1.18.25, installed
  locally), a categorically stronger source than reading a compiled binary's strings
  (#856, #857's situation): a plugin file exports an async `Plugin` function,
  `(input, options?) => Promise<Hooks>`; its own bundled example (`example.d.ts`)
  uses a named export, not `export default`.
- **Correction to the issue's own wording**: the issue said the approval event is
  `permission.asked`. The SDK's own `Hooks` interface (`@opencode-ai/plugin`'s
  `index.d.ts`) names it `"permission.ask"` — used here, against that authoritative
  source. `session.idle` (the turn-ended signal) is confirmed too, but delivered only
  through the generic `event` hook (`Hooks.event`), filtered to
  `event.type === "session.idle"` — there is no dedicated top-level hook key for it,
  unlike `permission.ask`.
- The plugin file (`locklane-bell.js`) is written as CommonJS
  (`module.exports.LocklaneBellPlugin = ...`), not the ESM `export const` OpenCode's
  own example uses. Verified empirically: a bare `.js` file with no `package.json`
  declaring it a module is CommonJS by Node's own resolution rules regardless of the
  syntax inside it — an ESM-syntax `.js` file in that position fails to load at all
  (`SyntaxError: Named export '...' not found ... is a CommonJS module`). A
  CommonJS-authored named export (`module.exports.X = ...`) is what Node's own
  cjs-module-lexer-based interop can actually expose as a named ESM import, confirmed
  by driving the real installed file through a real `import { LocklaneBellPlugin }
  from "..."` under a real pty (the same mechanism `PtySessionAttentionTest` and
  #856/#857's tests use) and seeing the bell arrive.
- Directory resolution (`OPENCODE_CONFIG_DIR` when set, else `~/.config/opencode`)
  moved out of Java and into one new Spring property,
  `locklane.opencode-config-dir: ${OPENCODE_CONFIG_DIR:${user.home}/.config/opencode}`
  (application.yml — outside this issue's literal Scope, touched by necessity: the
  new property is what the new component's `@Value` injection needs to exist at
  all). Reason for the redesign: an earlier version read `${user.home}` and
  `OPENCODE_CONFIG_DIR` directly in Java, which meant every `@SpringBootTest` in this
  module — not just a test of this class — constructed a real `OpenCodeBellPlugin`
  bean that wrote into the *actual* `~/.config/opencode/plugins/` on whatever machine
  ran the tests (caught by `TerminalWebSocketHandlerIntegrationTest` passing while
  quietly leaving a real file behind). `engine/src/test/resources/application.yml`
  now overrides `locklane.opencode-config-dir` to a temp path, the same "keep tests
  out of the real home directory" pattern `locklane.data-dir` already uses there.
  "Honouring `OPENCODE_CONFIG_DIR`" (the done-when's own phrase) is consequently
  Spring's property resolution, not bespoke Java logic — not itself unit-tested here,
  the same way `${locklane.data-dir}`'s own resolution isn't tested by
  `CodexBellHookScript`'s or `OmpBellHookExtension`'s tests either.
- The header/hash/overwrite-rule design: the first line of the file is the sole test
  for whether an existing file is "ours" (`HEADER`, a fixed constant); a
  `content-sha256` line is embedded for a human's benefit but is not itself part of
  the ownership check, only used to decide whether a write is even needed (already
  current → skip, matching `CodexBellHookScript`/`OmpBellHookExtension`'s
  no-unnecessary-mtime-churn choice). A file already carrying `HEADER` is always
  refreshed to the current content on version drift; a file that does not carry it
  is never touched, forever, even if it disappears and comes back later as something
  else non-Locklane.
- `TerminalWebSocketHandler` depends on `OpenCodeBellPlugin` in its constructor but
  never reads it — there is no argv change for `opencode`, so the dependency exists
  purely to keep this agent's bell-wiring documented and constructed alongside the
  other three, matching the issue's own Scope naming that file.

## Deviations / notes
- The Done-when's manual check — "in a Locklane agent tab, an OpenCode turn ending
  makes the amber dot appear... and a pending approval rings too" — was **not
  completed in a real tab**, the same sandbox reason recorded on #855/#856/#857 (no
  controlling terminal for this session's own shell, no browser, and OpenCode itself
  was not driven as a live interactive session for the same reason). What was
  verified instead: both hooks (`event` filtered to `session.idle`, and
  `permission.ask`) were exercised directly against the real installed plugin file,
  loaded as a real ES module the way OpenCode's own dynamic import would, under a
  real pty — each rings a bell; an unrelated event does not. A human should confirm
  the real-tab behavior for both trigger points before or shortly after this ships.
