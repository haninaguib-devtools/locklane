# 857 — Ring the bell from an OMP hook file when a turn ends or it needs input
Issue: #857 · Part of: #853

## Asked
Have OMP ring the terminal bell itself when it stops for the user. OMP loads a hook or extension file per run through its `--hook <file>` flag, so Locklane ships one file, `${locklane.data-dir}/hooks/omp-bell.js` (or the extension format the installed OMP expects), written by the engine at startup next to the Codex script, and passes the flag on every `omp` launch. The extension subscribes to the events that mean "stopped for the user", a turn ending and input or approval being requested, and on each writes a bell to the controlling terminal. The exact event names and the extension module shape are verified against the installed OMP (v18 at the time of writing) and recorded, since they are not documented in a form this task can cite.

## Done when
- After engine start, the OMP hook file exists under `${locklane.data-dir}/hooks/`; an engine test covers its creation and idempotence.
- `resolveLaunchCommand` and `seededLaunchCommand` produce, for `omp`, `omp <prompt>` and `omp --resume <id>`, an argv carrying `--hook=<that file>`; a unit test asserts it. Other commands are untouched.
- In a Locklane agent tab, an OMP turn ending makes the amber dot appear at that instant, and a pending approval rings too; the record names the events used.
- `./mvnw -B test` passes.

## Explicitly not
- Touching the user's OMP configuration.
- Any client change.

## Decisions made along the way
- OMP's extension API and event names are undocumented in any citable form, so they
  were read directly out of the installed OMP binary (`omp-cli v18.1.10`,
  `@openai`-style bundled Bun single-file executable at `~/.local/bin/omp`) via
  `strings` against the binary. The extension contract: a hook file's exported
  factory function is called with one `ExtensionAPI`-shaped argument exposing
  `.on(eventName, handler)`, confirmed from the class implementing it
  (`class _ks { on(e,t) { const s = this.extension.handlers.get(e) ?? []; ... } }`)
  and from the loader rejecting a file whose export "does not export a valid factory
  function". `module.exports = function (api) { ... }` (CommonJS) was used for the
  installed file, the safer choice for a standalone file with no `package.json`
  declaring module type.
- Event names used, each confirmed directly from OMP's own source strings — most
  tellingly, an internal example extension bundled inside OMP itself
  (`function uws() { return (e) => { ... }; }`) that hooks the *exact same three
  events*, for the exact same "when do we notify" purpose, this task needed:
  - `agent_end`, guarded by `!event.willContinue` — OMP's own example reads
    `r.willContinue` the same way to decide whether a turn genuinely ended (vs. an
    intermediate step in a longer auto-continuation OMP is about to keep running
    unattended). `session_stop` was considered and rejected: it fires on a distinct,
    narrower condition (`"session_stop continuation cap reached"`), not on an
    ordinary turn ending.
  - `tool_approval_requested` — an approval prompt about to be shown; OMP's own
    example maps this to `event: "permission_request"`.
  - `tool_execution_start`, guarded by `event.toolName === "ask"` — OMP's own
    built-in structured-question tool; OMP's own example maps this to `event:
    "question_asked", summary: "Waiting for your answer"`, textually the closest
    analogue to Claude Code's `AskUserQuestion` this task could find.
  - `input` was considered and rejected: it fires when the *user* types into OMP's
    own composer (an interception point for outgoing text), the opposite direction
    from "OMP is waiting for the user."
- `OmpBellHookExtension`, like `CodexBellHookScript`, always overwrites its file on
  every construction rather than only when absent — the same "idempotent means always
  correct, not merely present" reasoning (#856), covered by
  `aStaleExtensionIsOverwrittenRatherThanLeftAlone`.
- `--hook=<path>` is composed as one argv element (not two: `--hook` then the path),
  matching `omp --help`'s own documented form (`--hook=<value>`).
- `TerminalWebSocketHandler`'s constructor now takes both `CodexBellHookScript` and
  `OmpBellHookExtension`; the test-only constructors gained a second fixed path
  (`TEST_OMP_BELL_HOOK_EXTENSION`) alongside the existing codex one, following the
  same pattern #856 established.

## Deviations / notes
- OMP itself is not installed in CI (only in this development sandbox), so the unit
  test proving "running it rings a bell" loads the installed file under Node (OMP's
  own extension API is a plain `api.on(event, handler)` registry — reproducible with
  a fake `api` object with no need for OMP itself) and fires each of the three events
  under a real pty (the same mechanism `PtySession` and `CodexBellHookScriptTest` use)
  to prove the bell actually reaches a controlling terminal. This proves the
  extension's own logic is correct; it does not prove OMP itself will load and invoke
  it exactly this way — that still wants a real-tab check (below).
- The Done-when's manual check — "in a Locklane agent tab, an OMP turn ending makes
  the amber dot appear... and a pending approval rings too" — was **not completed in
  a real tab**, the same sandbox reason recorded on #855/#856 (no controlling terminal
  for this session's own shell, no browser). Unlike #856's Codex question, this task's
  event-name choices could not even be exercised against a live, running OMP session
  in this sandbox — they rest entirely on reading OMP's own bundled source strings,
  including its own example extension performing the identical hookup. A human should
  confirm the real-tab behavior for all three events (turn end, approval, structured
  question) before or shortly after this ships.
