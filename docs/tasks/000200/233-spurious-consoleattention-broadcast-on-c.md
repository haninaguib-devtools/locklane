# 233 — Spurious consoleAttention broadcast on console reattach after restart
Issue: #233

## Asked
When the engine reattaches to a console after a restart, it can spuriously broadcast
`consoleAttention: waiting` even though nobody is actually waiting for input. The new
shell's own startup output — an OSC window-title escape sequence, terminated by BEL, that
Debian/Ubuntu's default interactive `bashrc` emits as part of the prompt — is
misread as the "I'm done/waiting" bell signal (#130), because the bell-detection scan
treats every BEL byte as a real attention signal regardless of what it's the terminator
of.

## Done when
- A BEL byte that terminates an OSC escape sequence (`ESC ] ... BEL`, the window-title
  convention) no longer marks a session `WAITING`.
- A bare BEL — one not inside such a sequence — still marks a session `WAITING`, exactly
  as before (#130's existing guarantee, unchanged).
- `SessionRegistryConsolesChangedTest.reattachingAfterARestartWithAPersistedRecordButNoLiveSessionBroadcastsNothing`
  asserts the specific guarantee it's meant to check
  (`verify(broadcaster, never()).broadcast(eq("consolesChanged"), any())`) rather than
  the broader `verifyNoInteractions(broadcaster)`, decoupling it from the unrelated
  attention race.
- `./mvnw -B test` passes.

## Explicitly not
Nothing deferred — both pieces the issue named (production fix and test narrowing) are
in scope and done together.

## Decisions made along the way
- Implemented the "filter OSC-title sequences out of bell-detection" option from the
  issue's two suggested approaches, not the "ignore BEL before any write/markFocused"
  option: the latter only patches the race at startup, while an OSC-title BEL can recur
  any time the shell redraws its prompt (e.g. `PROMPT_COMMAND` on every command), so
  filtering by sequence shape is the fix that actually removes the false positive rather
  than narrowing the window it can occur in (haninaguib, 2026-08-27).
- The scan needs to be stateful across chunks (an escape sequence can straddle a
  `read()` chunk boundary), so `PtySession` now keeps the OSC-scan state on the drain
  thread's own instance fields rather than the previous static, chunk-local
  `containsBel` helper (haninaguib, 2026-08-27).

## Deviations / notes
- none
