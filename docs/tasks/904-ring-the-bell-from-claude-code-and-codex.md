# 904 — Ring the bell from Claude Code and Codex hooks by terminal path, not /dev/tty
Issue: #904

## Asked
The agents badge and sidenav dots no longer pulse amber when a Claude Code agent finishes a turn or needs input, because the bell hook Locklane injects (#855) never reaches the engine's PTY. Claude Code runs every hook command in a detached session with no controlling terminal — verified on 2.1.267, 2.1.268 and 2.1.269 with a Stop hook under a real pty: the hook's `sid` equals its own pid, `tty` reports "not a tty", and `printf '\a' > /dev/tty` fails with "No such device or address". #862 then disabled the quiet-output fallback for any agent launched with a bell hook, leaving that never-working hook as the only attention signal, and #880 silenced the hook's failure with `2>/dev/null || true`, so nothing surfaces at all. Make the hook ring the engine's PTY by writing to the terminal device by path instead of `/dev/tty`: capture the device path before the agent starts (e.g. launch through `sh -c 'export LOCKLANE_TTY=$(tty); exec "$@"' sh claude ...`, or an equivalent the engine can set) and have the Claude Code hook command write `\a` to `"$LOCKLANE_TTY"`. This was verified on a nested Claude Code run under a real pty: the hook's parent is the `claude` process, the variable passes through, and the write succeeds. `CodexBellHookScript`'s `bell.sh` carries the same `/dev/tty` line and Codex's notify command is launched the same way, so cover it identically. Keep the best-effort wrapping from #880 (unset or unwritable path → silent, exit 0).

## Done when
- In a real Locklane tab, a Claude Code agent that ends a turn or asks for input makes the header agents badge and the sidenav dot for its issue/project pulse amber, and both clear on the next keystroke. Checked by a human on this branch's build, and the result noted in the task record.
- The same check passes for a Codex agent in a Locklane tab, or is recorded as not verified with the reason.
- `TerminalWebSocketHandlerLaunchCommandTest` asserts the Claude Code launch command captures the terminal device path before exec and that the injected hook command writes to it by path, never to `/dev/tty`; `CodexBellHookScriptTest` asserts the same for `bell.sh`.
- A unit test launches the hook command from a child that has been `setsid`-detached from a real pty (the shape Claude Code uses) with the variable set to that pty's slave path, and the BEL arrives on the pty's master side; with the variable unset the command is silent and exits 0.
- The quiescence fallback stays disabled for hooked agents (`Launch.quiescenceFallbackEnabled` unchanged for `claude`, `codex`, `omp`, `opencode`), with the existing tests still green.
- `docs/adr/113-claude-code-hooks-ring-the-engines-bell.md` gains a short note that a Claude Code hook has no controlling terminal and the bell therefore targets the captured device path.
- `./mvnw -B -pl engine -am -Dskip.npm test` passes.

## Explicitly not
- The OMP extension (#857) and OpenCode plugin (#858): they run inside the agent's own process, which does hold the controlling terminal, and are not known to be broken.
- Re-enabling the quiet-output fallback for hooked agents (#862 stands).
- Any change to the client's attention store, badge, or notification code (#859, #884, #885) — they work once a `waiting` event arrives.
- Changing how Claude Code itself spawns hooks.

## Decisions made along the way
- Captured the controlling terminal's device path via a shell wrapper
  (`sh -c 'if TTY=$(tty 2>/dev/null); then export LOCKLANE_TTY="$TTY"; fi; exec "$@"' sh <agent-argv...>`)
  around the whole `claude`/`codex` launch, rather than having `PtySession` set the
  variable itself — the wrapper only needs to change `TerminalWebSocketHandler`
  (which already owns argv composition for these two agents), and the issue's own
  Scope calls out `PtySession.java` as in-scope "only if the engine sets the
  variable itself rather than via a shell wrapper", so `PtySession.java` is
  untouched.
- `withClaudeBellHooks`/`withCodexBellNotify` each apply the wrapper as their own
  last step, so every one of their six call sites (plain, resumed, seeded — both
  agents) gets it for free with no change at those call sites themselves.
- OMP and OpenCode are untouched, per the issue's own non-goals: both run their bell
  mechanism inside the agent's own process, which already holds the controlling
  terminal.

## Deviations / notes
- `TerminalWebSocketHandlerRestartResumeTest` and `TerminalWebSocketHandlerTemplateSeedTest`
  (not named in the issue's Scope) needed the same test-side update as
  `TerminalWebSocketHandlerLaunchCommandTest` — they assert the exact claude/codex
  argv shape too, which the new wrapper changes. No production behavior in either
  file's own subject (restart-resume capture, template seeding) changed.
- The "checked by a human in a real Locklane tab" done-when items (Claude Code and
  Codex) are **not yet verified** — this session has no real Locklane tab to attach
  a live agent session to. Recorded here as outstanding rather than claimed done;
  needs a human check on this branch's build before shipping with confidence, though
  the mechanism is covered end-to-end by `BellHookCommandDetachedShapeTest` (a real
  detached child of a real pty, the same shape Claude Code and Codex each run their
  hook in) and by the unit tests asserting the composed launch command.

## Agents
- work: claude-code / claude-sonnet-5
