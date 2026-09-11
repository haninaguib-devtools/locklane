# 880 — Make the Claude Code and Codex bell hooks silent when there is no controlling terminal
Issue: #880

## Asked
When a Claude Code session launched by Locklane has no controlling terminal, every turn now ends with a hook error shown to the user and fed back to the model:

```
Stop hook error: [printf '\a' > /dev/tty]: /bin/sh: 1: cannot create /dev/tty: No such device or address
```

Since #855 the engine passes `--settings` to every `claude` launch with three hooks (`Stop`, `Notification` on `permission_prompt`, `PreToolUse` on `AskUserQuestion`) that each run `printf '\a' > /dev/tty`. That command assumes `/dev/tty` can be opened for writing. In a session where the process has no controlling terminal, the redirection fails, the shell exits non-zero, and Claude Code reports the failure as hook feedback. The `Stop` hook is the worst case: its error is delivered to the model as a new turn, so the model is re-invoked after every stop for nothing, and the transcript fills with acknowledgements of the same error. Observed on 2026-09-11 in a Locklane workarea session whose `claude` process was launched with the `--settings` argument but had no controlling terminal.

The OMP extension written for #857 already handles this case: it wraps the `/dev/tty` open in a try/catch and stays silent when there is nothing to ring. The Claude Code one-liner and the Codex script from #856 (`~/.locklane/hooks/bell.sh`) do not. Make the shell-based hooks best-effort the same way: ring the bell when a controlling terminal exists, exit 0 silently otherwise, and never emit anything on stdout or stderr that Claude Code would surface.

## Done when
- The hook command the engine injects for `claude` succeeds with no output when `/dev/tty` cannot be opened, for example `[ -w /dev/tty ] && printf '\a' > /dev/tty 2>/dev/null || true`, and still rings when it can. The launch-command unit test asserts the new command text.
- `~/.locklane/hooks/bell.sh` (Codex, #856) applies the same guard, and its unit test covers it.
- Running `sh -c "<hook command>"` with no controlling terminal (for example under `setsid`) exits 0 and prints nothing to stdout or stderr.
- A Claude Code session started by Locklane without a controlling terminal shows no `Stop hook error` line after a turn ends, and the model receives no hook feedback; the record states this was checked in a real session.
- A session in a Locklane tab still gets the amber dot at the instant a turn ends, so the #855 behaviour is unchanged where the terminal exists.
- `./mvnw -B test` passes.

## Explicitly not
- Changing the OMP extension (#857) or the OpenCode plugin (#858), which already fail silently.
- Changing the engine's bell detection or the quiet-output fallback.
- Working out why a given launch path ends up with no controlling terminal; the hook must be harmless there regardless.

## Decisions made along the way
- Used `{ printf '\a' > /dev/tty; } 2>/dev/null || true` rather than the issue's example
  `[ -w /dev/tty ] && printf '\a' > /dev/tty 2>/dev/null || true`. Verified locally
  (Python `pty`/`setsid`) that `[ -w /dev/tty ]` only checks the device node's static
  0666 permission bits, which are always true, so it never actually detects "no
  controlling terminal" — the real failure is the shell's own attempt to open
  `/dev/tty` for the `>` redirect, which happens before the guarded command runs and
  is not caught by that command's own `2>/dev/null`. Wrapping the redirection itself
  in `{ ...; } 2>/dev/null` catches that open failure; `|| true` then keeps the exit
  status zero. Confirmed with a real pty (rings a BEL) and under `setsid` with no
  controlling terminal (exits 0, no stdout/stderr either way).
- Added a dedicated no-controlling-terminal test to each of the two test files, rather
  than only updating the existing string-match assertions: a plain `ProcessBuilder`
  child (pipes, not a pty) reproduces the bug's shape and asserts exit 0 with empty
  stdout/stderr, which the issue's done-when calls for as a distinct check.

## Deviations / notes
- The done-when's "a Claude Code session started by Locklane without a controlling
  terminal shows no `Stop hook error` line ... checked in a real session" was not
  performed: this task was worked from an automated `/t-drive` session with no way to
  drive a second, separately-launched Locklane tab/session to observe. The engine unit
  tests above exercise the same command and script directly (real pty for "still
  rings", plain ProcessBuilder for "no controlling terminal, silent, exit 0"), which is
  the mechanism the fix changes; a human should still confirm once in a real tab. Same
  gap as initiative #853's tasks, which shipped without a real-tab check for the same
  sandbox reason (see #853).
