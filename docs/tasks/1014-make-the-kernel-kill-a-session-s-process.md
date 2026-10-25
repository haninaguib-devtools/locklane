# 1014 — Make the kernel kill a session's process, not the engine, when memory runs out
Issue: #1014 · Part of: #1013

## Asked

When the machine runs out of memory, the kernel should kill a process belonging to an agent session, never the Locklane engine.

Today every process in `locklane.service` has `oom_score_adj=200`, the default for user units. The engine is often the largest single process in the unit, so the kernel's OOM killer picks it. Because it is the unit's main process, systemd then stops the unit and every session dies with it (24 October 2026: two engine kills, at 16:03 and 16:16).

The change gives the engine and the sessions different OOM priorities:
- **The engine:** the unit gets `OOMScoreAdjust=100`. This is the lowest value an unprivileged user service may set, because the user manager itself runs at 100.
- **Each session:** every session the engine starts through `PtySession` begins with a higher `oom_score_adj`, for example 500. Raising the value needs no privilege. Every process the agent starts (builds, test workers, browsers, agent stacks) inherits it. Writing `/proc/self/oom_score_adj` in the launch wrapper or using util-linux `choom` are two possible mechanisms; the plan settles which.

The kernel then kills inside a session. `OOMPolicy=continue` (#1009) keeps the engine and the other sessions running.

## Done when

- The unit that `scripts/locklane` writes contains `OOMScoreAdjust=100`. The lifecycle test (`scripts/tests/lifecycle/run.sh`) checks for it, as it already does for `OOMPolicy=continue`.
- On Linux, with Locklane installed:
  - `cat /proc/<engine pid>/oom_score_adj` prints `100`.
  - For a running session's agent process, and for a child process it starts, it prints the chosen higher value.
- An existing install picks up the new unit setting on update (unit rewritten, then `systemctl --user daemon-reload`) without a manual step.
- On macOS (launchd), sessions start as today.
- Human-judged: in a reproduced memory exhaustion on Linux, the kernel kills a session's process. The engine and the other sessions keep running.

## Explicitly not

- Memory limits or per-session scopes. That is the sibling task in this initiative.
- Changing which process inside a session the kernel picks.

## Decisions made along the way
- **Session score: 500.** The engine runs at 100 (`OOMScoreAdjust=100` in the unit
  `scripts/locklane` writes); every session starts at 500, so the OOM killer's badness
  for a session process far outweighs the engine's at comparable sizes.
- **Mechanism: a `/bin/sh` launch wrapper, not `choom` and not a post-start write.**
  `PtySession.withRaisedOomScore` spawns `/bin/sh -c '{ echo 500 > /proc/self/oom_score_adj; } 2>/dev/null; exec "$@"' locklane-session <command...>`.
  The shell sets its own score and `exec`s the command under the same pid, so the
  score is in place before the agent can start anything and every descendant inherits
  it. `choom` is util-linux and not guaranteed to be installed; writing
  `/proc/<pid>/oom_score_adj` from the engine after `start()` races the session's first
  forks (a `.bashrc` subshell or an early agent child would keep 100). A failed write is
  ignored and the command still runs.
- **Where it applies:** wherever `/proc/self/oom_score_adj` exists (Linux). On macOS
  the command is spawned exactly as before.
- **A missing program still fails to start.** pty4j throws for a program that is not
  an executable (probed: `IOException: Exec_tty error:Unknown reason`, which becomes
  `PtySessionStartException`). Behind the wrapper the shell would instead start and
  exit 127, registering a session that never ran. So the command is wrapped only when
  its program resolves (a path, relative to the working directory, or a name on the
  session environment's `PATH`, via `InstalledAgentDetector.detect`); otherwise it is
  left unwrapped and fails as today.
- **Update path:** `locklane update` already re-runs `register`, which rewrites the
  unit and runs `systemctl --user daemon-reload` before restarting, so an existing
  install picks up `OOMScoreAdjust=100` with no manual step. The new lifecycle scenario
  `systemd-update-rewrites-unit` proves it: the systemctl stub now logs its calls to
  `$STUB_STATE/systemctl-calls`.

## Deviations / notes
- The issue's "the plan settles which" mechanism: the scope touches no protected path,
  so no `/t-plan` ran; the choice is recorded above instead.
- The two new lifecycle scenarios fail with the unit line removed (checked) and pass
  with it.
- Not verified here (human-judged): `/proc/<engine pid>/oom_score_adj` = 100 on a real
  install after update, and a reproduced memory exhaustion killing a session's
  process. `PtySessionOomScoreTest` checks a real PTY session's process and its child
  both read 500 on Linux.

## Agents
- work: claude-code / claude-opus-5-5
