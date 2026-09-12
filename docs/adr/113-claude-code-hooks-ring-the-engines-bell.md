# ADR-113: Claude Code's own hooks ring the engine's bell; the bell stays the contract

**Status:** Accepted · 2026-09-10
**Deciders:** project owner *(solo phase; ratified by the human's confirmation at
`/t-ship`'s gate on task #855, under initiative #853)*

## Context

`PtySession` (#130) marks a session as waiting for the user on two signals: a bare
BEL byte in its output, and output gone quiet for `QUIESCENCE_THRESHOLD_MS` with no
input since. The bell is precise — it fires the instant an agent stops — while the
quiescence fallback exists only for an agent that never rings one, and necessarily
lags behind the real moment a turn ended.

Claude Code, launched inside a Locklane agent tab, rings no bell of its own: it ships
a "hooks" mechanism that runs a shell command at named lifecycle points, and separately
a notification-channel setting, but neither is wired to the engine's bell by default,
and inside a Locklane tab there is no user around to have configured either one. Claude
Code accepts `--settings <json>` as an argv element carrying inline settings that merge
with the user's own, without touching any file on disk, so the engine can wire the
hooks itself, on every launch, with nothing for the user to install or configure.

Three of Claude Code's own hook points together cover every way a turn stops waiting
for the user: `Stop` (a turn ended in prose), `PreToolUse` matched on
`AskUserQuestion` (a structured question about to be shown), and `Notification`
matched on `permission_prompt` (an approval pending). A hook's stdout reaches Claude
Code itself, never the screen, so the hook command must write directly to the
controlling terminal — inside a Locklane tab, the engine's own PTY — which is exactly
what `PtySession`'s bell scanner watches.

This is the first of several tasks (initiative #853) that wire an agent CLI's own
completion signal to the bell; Codex, OMP and OpenCode each get their own task and
their own mechanism, since none of the four share a hook or notification format.

## Decision

Locklane wires each agent CLI's own hook (or equivalent) mechanism to ring the
engine's bell, rather than asking the engine to recognize each CLI's own
notification format, or asking the user to configure anything:

- `TerminalWebSocketHandler`, which composes every agent's launch command in one
  place, appends `--settings` and one JSON argv element to every `claude` launch —
  plain, resumed, or seeded with a first prompt alike — declaring the three hooks
  above, each running `printf '\a' > "$LOCKLANE_TTY"` (see the #904 note below for
  why this targets a captured device path rather than `/dev/tty` directly).
- The hook command, and Codex's equivalent `bell.sh` (#856), are best-effort
  (#880): an unset or unwritable path leaves nothing to open, and both wrap the
  redirection so a failed open exits 0 and prints nothing, rather than surfacing as a
  `Stop hook error` fed back to the model on every turn. This is the same failure
  shape the OMP extension (#857) already handles with a try/catch around its own
  `/dev/tty` open.
- The engine's own contract stays exactly what `PtySession` already scans for: a bare
  BEL on the controlling terminal. Claude Code's hooks are a producer of that signal,
  not a new signal the engine has to learn.
- The quiescence fallback (#130) is untouched and stays the catch-all for any agent
  CLI with no wired hook yet, or a hook that fails to run.
- The user installs and configures nothing: the settings are inline JSON on the argv,
  never written to the user's own `settings.json`, and merge with whatever hooks a
  user has already configured for themselves.

## Rationale

- One agent-agnostic contract (the bare bell) is simpler for the rest of the engine
  and the client to reason about than teaching `PtySession` every CLI's own
  notification format.
- `--settings` merging inline JSON, rather than writing to the user's settings file,
  means this never collides with or overwrites a user's own hook configuration, and
  leaves no trace once the process ends.
- Writing to `/dev/tty` rather than stdout is the only way a hook's output reaches the
  screen at all; stdout is captured by Claude Code itself and never displayed.
- Composing the argv once in `TerminalWebSocketHandler`, alongside the resume and seed
  logic that already lives there, keeps every `claude` launch's command-line assembly
  in one place rather than spreading agent-specific knowledge across the codebase.

## Alternatives considered

- **Rely on Claude Code's own notification-channel setting** — rejected: it requires
  the user to configure a channel Locklane does not control the shape of, and still
  would not cover the `AskUserQuestion`/`permission_prompt` cases the same way three
  explicit hooks do.
- **Teach `PtySession` to recognize each CLI's own completion output** — rejected: it
  would grow the engine's bell scanner into a per-CLI parser, exactly the coupling the
  bare-bell contract (#130) exists to avoid.
- **Write the hooks to the user's own `~/.claude/settings.json`** — rejected: it
  persists past the session, could collide with a user's own hooks, and needs cleanup
  logic this task's `--settings` argv approach makes unnecessary.

## Addendum (#904): a Claude Code hook has no controlling terminal at all

Verified against Claude Code 2.1.267, 2.1.268 and 2.1.269 under a real pty: Claude
Code runs a hook command in a detached child with no controlling terminal of its own
— the hook's own session id equals its pid, `tty` reports "not a tty", and
`printf '\a' > /dev/tty` fails with "No such device or address". `/dev/tty` was
therefore always unreachable from the hook, and #880 only silenced that failure
rather than fixing the bell. Codex's `notify` command runs the same detached way.

The bell now targets a captured device path instead: `TerminalWebSocketHandler`
wraps every `claude`/`codex` launch's whole command in a shell that captures this
launch's own controlling terminal (inside a Locklane tab, the session's own PTY) into
`LOCKLANE_TTY` before exec'ing the agent, which the hook — however detached — still
inherits through the environment. Both hook commands write to `"$LOCKLANE_TTY"` by
name rather than opening `/dev/tty`.

## Consequences / revisit triggers

- Each later task in initiative #853 (Codex, OMP, OpenCode) records how it wires that
  CLI's own mechanism to the same bell, following this ADR's shape rather than
  repeating its reasoning.
- If Claude Code changes `--settings`' merge behavior, or a future version needs a
  different hook shape to cover the same three stopping points, this ADR is revisited
  rather than silently drifting from what it records.
- If Locklane ever needs to distinguish *why* Claude Code stopped beyond bell-vs-quiet
  (#854), that distinction is carried on the engine's own event, not by adding more
  CLI-specific signals here.
