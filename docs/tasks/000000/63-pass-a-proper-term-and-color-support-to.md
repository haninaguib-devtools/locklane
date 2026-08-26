# 63 — Pass a proper TERM (and color support) to console sessions
Issue: #63

## Asked
Console sessions run a shell/agent CLI process with whatever environment the engine
happens to have — but the engine has no `TERM` variable at all (confirmed: it's launched
from the IDE, not a terminal). CLIs like Claude Code use `TERM` to decide whether to emit
color output; with none set, they fall back to plain, colorless output — so the usual CLI
colors never show up in a console tab.

## Done when
- A new console session's process environment always includes a real `TERM` (e.g.
  `xterm-256color`) and `COLORTERM` (e.g. `truecolor`), regardless of what the engine
  process itself was started with.
- Running `claude` (or any other ANSI-color-aware CLI) in a console tab shows normal
  color output, not plain text.
- A test (or documented manual check) confirms the PTY's environment carries
  `TERM`/`COLORTERM` even when `System.getenv()` doesn't have them.

## Explicitly not
none

## Decisions made along the way
- none yet

## Deviations / notes
- none
