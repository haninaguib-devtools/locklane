# 856 — Ring the bell from Codex's notify command when a turn ends
Issue: #856 · Part of: #853

## Asked
Have Codex ring the terminal bell itself when it stops for the user. Codex runs a configurable notify command when an agent turn completes, passing a JSON payload as the command's argument, and the setting can be overridden per launch with `-c notify=[...]`, so Locklane needs no change to the user's Codex config. The command is a one-line script Locklane owns, `${locklane.data-dir}/hooks/bell.sh`, which ignores its argument and writes a bell to the controlling terminal (`printf '\a' > /dev/tty`), the same contract the Claude Code task establishes. The engine writes that script, executable, under its data directory at startup, idempotently, so it exists before any tab launches. Whether Codex's notify also fires when an approval is pending is not settled by its documentation; the task checks it against the installed Codex and records the answer, and if it does not fire, the record says so plainly rather than papering over it with the quiet-output fallback.

## Done when
- After engine start, `${locklane.data-dir}/hooks/bell.sh` exists, is executable, and running it rings a bell on the caller's controlling terminal; an engine test covers the file's creation and idempotence.
- `resolveLaunchCommand` and `seededLaunchCommand` produce, for `codex`, `codex <prompt>` and `codex resume <id>`, an argv carrying `-c` and a `notify=[...]` value naming that script; a unit test asserts it. Other commands are untouched.
- In a Locklane agent tab, a Codex turn ending makes the amber dot appear at that instant; the record states what happens on a pending approval.
- `./mvnw -B test` passes.

## Explicitly not
- Touching `~/.codex/config.toml`.
- Any client change.

## Decisions made along the way
- `CodexBellHookScript` (new, `engine/src/main/java/dev/locklane/engine/agent/`) always
  overwrites `<data-dir>/hooks/bell.sh` and re-applies its executable bit on every
  construction (once per engine start), rather than only writing when the file is
  absent — "idempotent" here means safe and correct to run repeatedly, including
  self-healing a stale script from an older running version or one a user tampered
  with, never "skip if present." Covered by
  `aStaleOrTamperedScriptIsOverwrittenRatherThanLeftAlone`.
- `resolveLaunchCommand`/`seededLaunchCommand` were instance methods already reachable
  through `handler.resolveLaunchCommand(...)`/`handler.resolveLaunch(...)` in most
  tests, but the two lowest-level overloads were `static` — needed to become instance
  methods so they could read the injected `CodexBellHookScript`'s path. Every test that
  called them as `TerminalWebSocketHandler.resolveLaunchCommand(...)` (a static
  reference) now goes through a constructed instance instead; a `TEST_CODEX_BELL_NOTIFY_SCRIPT`
  fixed path backs every test-only constructor, so no test needs a real
  `CodexBellHookScript`.
- Codex's `notify` override checked empirically against the installed CLI
  (`codex-cli 0.153.4`, `@openai/codex`), via `codex exec -c 'notify=[...]'`: it fires
  exactly once per completed turn, with the payload
  `{"type":"agent-turn-complete","thread-id":...,"turn-id":...,"cwd":...,"client":...,"input-messages":[...],"last-assistant-message":...}` —
  matching the issue's own description and confirming `-c notify=[...]` is accepted
  and honored.
- Whether `notify` also fires on a pending approval: **not conclusively confirmed
  either way**, recorded here plainly per the issue's own instruction rather than
  papered over. What was established:
  - Every observed `notify` invocation carried `"type":"agent-turn-complete"`; none
    carried any other `type` in any run, including attempts specifically crafted to
    provoke an approval prompt (`approval_policy="untrusted"`, `--sandbox read-only`,
    asking Codex to write a file).
  - The installed Codex binary's own strings show a *separate*, newer hooks subsystem
    (`codex_hooks`, `hooks.json`) with its own `PermissionRequest` event, distinct
    from the classic `notify` config key — suggesting approval notifications are that
    subsystem's job, not `notify`'s.
  - The attempts to reach a live approval prompt ran in a sandbox with no real
    terminal emulator (no controlling tty for this session's own shell, no browser),
    the same limitation noted on #855; a session got as far as Codex's own
    trust-folder dialog before the harness's improvised terminal-query answering fell
    short of what a real interactive session needs, so a genuine approval-pending
    moment was never cleanly reached.
  - Conclusion recorded for the next reader: **do not rely on `notify` firing for a
    pending Codex approval** — treat it as turn-completion-only until proven
    otherwise in a real tab — meaning the quiescence fallback (#130) is very likely
    still what marks a Codex tab waiting during an approval prompt.

## Deviations / notes
- The Done-when's manual check — "in a Locklane agent tab, a Codex turn ending makes
  the amber dot appear at that instant" — was **not completed in a real tab**, for the
  same sandbox reason recorded on #855 (no controlling terminal for this session's own
  shell, no browser). What was verified instead, all against the real installed Codex
  CLI: `-c notify=[...]` is accepted, and `codex exec` actually invokes the configured
  script with the exact JSON payload described above on every completed turn. The
  engine-side half (a bare BEL on a real `pty4j` PTY marks waiting) is unchanged,
  already-covered behavior from #130/#854/#855. A human should confirm the real-tab
  amber-dot behavior, and ideally also settle the pending-approval question above in
  an actual interactive session, before or shortly after this ships.
