# 713 — Resume omp past sessions instead of launching fresh
Issue: #713

## Asked
When someone reopens a past `omp` session from the console's session list, the engine should relaunch `omp` pointed at that same session — same as claude/codex/opencode. Currently falls through to bare `{"omp"}` with no resume flag.

## Done when
- `resolveLaunchCommand(cmd, resume)` returns `{"omp", "--resume", <id>}` when cmd is omp and resume valid, test in TerminalWebSocketHandlerLaunchCommandTest.
- Restart-reattach overload also resumes omp after engine restart, test in TerminalWebSocketHandlerRestartResumeTest.
- `isAgent` and `seededLaunchCommand` recognize "omp" for seeded first-prompt launches.
- `ConsoleSessionTitles` gets omp branch for auto-generated titles.
- `./mvnw -B test` passes.
- Stale javadoc (TerminalWebSocketHandler class doc cmd=<claude|codex|shell>, ConsoleResumeSessionRecord tool field "claude" or "codex" only) updated to mention opencode and omp.

## Explicitly not
- No changes to ResumeIdScanner or resume-id capture/storage pipeline.
- No changes to client session-list UI.

## Decisions made along the way
- omp resume takes `--resume <uuid>` (per `ResumeIdScanner`'s omp pattern and `omp --help`: `--resume`, `-r`, `--session` equivalent; Done-when pins `--resume`), and omp ids are UUIDv7 so the existing `RESUME_ID` gate needed no change.
- omp seeded first prompt rides positionally (`omp <prompt>`, as `omp --help` examples show), same as claude/codex.
- omp titles are read from the session's own `<timestamp>_<id>.jsonl` (`{"type":"title","title":...}` line, `{"type":"session",...}` line as fallback, last non-blank wins); the lookup walks `<agentDir>/sessions` once per batch for `*_<id>.jsonl` instead of reproducing omp's per-directory naming, so it stays correct if that naming changes. Agent dir honors `$PI_CODING_AGENT_DIR`, default `~/.omp/agent` (confirmed against the live sessions on this machine).
- Full `./mvnw -B test`: 857 run, 12 failures + 1 error, all reproduced verbatim on the clean tree (host git/gh config, PTY liveness, `/bin/true` sandbox) — pre-existing, unrelated.

## Deviations / notes
- none
