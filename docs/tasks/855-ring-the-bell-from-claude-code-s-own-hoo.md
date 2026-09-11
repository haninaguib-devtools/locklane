# 855 — Ring the bell from Claude Code's own hooks when a turn ends or it needs input
Issue: #855 · Part of: #853

## Asked
Have Claude Code ring the terminal bell itself at every point it stops for the user, so the engine's bell detection (#130) fires precisely instead of waiting for the quiet-output fallback. With its default notification channel, Claude Code rings nothing inside a Locklane PTY. Claude Code accepts a `--settings` argument carrying inline JSON that merges with the user's own settings, and its hooks can run a command at three points that together cover every stop: the `Stop` event when a turn ends (a question asked in prose), a `PreToolUse` hook matched on `AskUserQuestion` (a structured question about to be shown), and a `Notification` hook matched on `permission_prompt` (an approval pending). Each hook runs the same one-liner, `printf '\a' > /dev/tty`, writing to the controlling terminal rather than stdout, because hook stdout goes to Claude Code, not the screen. Inside a Locklane tab the controlling terminal is the engine's PTY, and a bare bell is exactly what the engine's scanner counts as attention (it ignores only a bell terminating an escape sequence). The engine composes every agent command line in one place, `TerminalWebSocketHandler`, for the plain, seeded-prompt and resume launches; every `claude` launch gains the argument there. The decision, that Locklane wires each agent CLI's own hook to ring the bell and the engine's contract stays the bell, is recorded as a consumer-local ADR, since later tasks repeat the pattern for Codex, OMP and OpenCode.

## Done when
- `resolveLaunchCommand` and `seededLaunchCommand` produce, for `claude`, `claude <prompt>` and `claude --resume <id>`, an argv that includes `--settings` followed by one JSON element declaring the three hooks above; an engine unit test asserts the argv and parses the JSON. Non-Claude commands are untouched.
- In a Locklane agent tab whose user settings carry no hooks of their own, asking Claude Code something makes the amber dot appear at the instant the turn ends, and answering "no" to a permission prompt or a structured question also rings; the record states this was checked in a real tab, including that a hook could open the controlling terminal for writing.
- `docs/adr/113-*.md` records the decision: the agent's own hook rings the bell, the bell stays the engine's agent-agnostic contract, the quiet-output rule stays as the fallback, and the user installs and configures nothing.
- `./mvnw -B test` passes.

## Explicitly not
- Touching the user's own Claude Code settings, or relying on its notification channel setting.
- Wiring any other agent; each has its own task.

## Decisions made along the way
- Every `claude` argv gets `--settings` appended, not only the two shapes the Done-when
  names explicitly (`claude <prompt>` and `claude --resume <id>`) — the Goal text says
  "every `claude` launch gains the argument", so the plain, unresumed, unseeded launch
  (`claude`) carries it too. `withClaudeBellHooks` is one private helper all three
  `TerminalWebSocketHandler` call sites (`resolveLaunchCommand`'s plain and resume
  branches, `seededLaunchCommand`) go through.
- The settings JSON is built via Jackson (`ObjectMapper`, already a dependency here)
  from `Map`/`List` literals rather than a hand-written string, so its escaping is
  correct by construction rather than by careful hand-editing.
- `engine/src/test/java/dev/locklane/engine/ws/TerminalWebSocketHandlerTest.java`
  named in the issue's Scope does not exist — this repo's tests for
  `TerminalWebSocketHandler` are already split by concern
  (`TerminalWebSocketHandlerLaunchCommandTest`, `…RestartResumeTest`,
  `…TemplateSeedTest`, …). The new argv/JSON test lives in
  `TerminalWebSocketHandlerLaunchCommandTest`, the existing home for
  `resolveLaunchCommand`/`seededLaunchCommand` coverage — the right file for this
  issue's intent, even though the literal filename in Scope doesn't exist.
- `TerminalWebSocketHandlerRestartResumeTest` and `TerminalWebSocketHandlerTemplateSeedTest`
  (outside the issue's literal Scope list) needed updating too: several of their
  existing assertions pinned an exact `claude` argv that this change necessarily
  extends. Updating them is a direct, unavoidable consequence of the sanctioned
  behavior change, not scope drift — leaving them broken would violate "checks are
  never weakened to pass."

## Deviations / notes
- The Done-when's manual check — "checked in a real tab" that a hook can open the
  controlling terminal and ring the bell at the right three moments — was **not**
  completed. This session's own shell has no controlling terminal at all (`tty` →
  "not a tty", `/dev/tty` → ENXIO) and no browser, so there is no way to open an
  actual Locklane tab and drive a real login/WebSocket/xterm.js session from here.
  What was verified instead, all from this sandbox:
  - `claude --settings <file-or-json>` is a real, documented flag (`claude --help`).
  - The exact JSON `TerminalWebSocketHandler` now produces is well-formed and matches
    Claude Code's own hooks schema (`Stop`, `PreToolUse`+matcher, `Notification`+matcher,
    each a `hooks` array of `{type:"command", command}`).
  - The engine-side half of the mechanism — a bare BEL on a real `pty4j` PTY (the exact
    kind Locklane's `PtySession` uses) flips attention to waiting, and a BEL that only
    terminates an OSC title sequence does not — is covered by
    `PtySessionAttentionTest` (already true before this task; unchanged by it).
  - An ad hoc attempt to drive a full interactive `claude` session against a raw
    `pty.openpty()` pair (no real terminal emulator answering cursor/DA queries)
    got as far as the trust-folder dialog and a spurious OSC-title BEL, but could not
    reliably reach a genuine Stop-hook bell — confirming a raw pty with nobody
    answering terminal queries is not equivalent to a real xterm.js-driven tab, not
    that the hook mechanism itself is broken.
  A human should confirm the real-tab behavior (amber dot on Stop, on a declined
  permission prompt, and on a declined structured question) before or shortly after
  this ships, the same way other tasks in this project have deferred a human-only
  check (e.g. the macOS launchd human checks noted on #678/#691).
