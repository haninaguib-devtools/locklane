# 1015 — Run sessions in their own scopes under a memory-limited slice
Issue: #1015 · Part of: #1013

## Asked

Bound the memory that agent sessions can use together, so that a spike is resolved among the sessions instead of across the whole machine. It should never reach the engine, the desktop or the other services the machine runs. When something must be killed, it should be one session, not all of them.

The engine starts each session through `PtySession`. Those sessions stay in `locklane.service`'s own control group, which has no memory limit (`MemoryMax=infinity`). This has two consequences:
- **A runaway session reaches the whole machine.** Its memory pressure falls on the engine, the desktop and every other service, until the kernel's global OOM killer picks a victim somewhere.
- **`systemd-oomd` would take everything at once.** It is active on Ubuntu, and it watches `user@1000.service` (limit: 50% memory pressure sustained for 20 seconds). When it acts, it kills a whole control group. Today the group holding all the sessions is `locklane.service`, so it would take the engine and every session together.

The change starts each session in its own transient scope under a Locklane sessions slice, and the slice carries a memory limit. When the sessions together reach the limit, the kernel kills a process inside the slice, and `systemd-oomd` picks one session's scope. The engine already uses this pattern for the desktop IDE: `DesktopIdeLauncher` wraps its command in `systemd-run --user --scope --quiet --collect` when `systemd-run` is available.

Decisions for `/t-plan`:
- **The limit.** A slice-wide `MemoryHigh`/`MemoryMax` with a default derived from total RAM, a setting the person can change, or both. Whether each session also needs its own limit.
- **Stop, restart and update behaviour.** A session in its own scope is no longer in `locklane.service`'s control group, so `systemctl --user stop locklane` no longer kills it by itself. Decide whether stop and update still end sessions, as today, or deliberately leave them running. Confirm the engine still sees a session's exit.
- **How this works with the OOM priority** from the blocking task, so the session's raised `oom_score_adj` still applies inside the scope.

## Done when

- On Linux with `systemd-run` available, each session's process tree runs in its own scope under the sessions slice. `/proc/<agent pid>/cgroup` shows the slice and scope.
- `systemctl --user show <sessions slice> -p MemoryHigh,MemoryMax` shows the agreed limit.
- Human-judged: a session that pushes the slice past its limit is killed (or has a process killed) inside the slice. The engine, the other sessions and processes outside Locklane are untouched.
- Stop, restart and update behave as the plan decided, and the lifecycle test covers it.
- Without `systemd-run`, or on macOS, sessions start as today.

## Explicitly not

- Capping parallelism inside a project's own build or tests. Each project owns that.
- Resuming sessions after the engine restarts.

## Decisions made along the way
The issue left three decisions to `/t-plan`. The scope touches no protected path, so
no plan stage ran, and they are settled here.

- **The limit: a slice-wide default relative to RAM, changeable with systemd's own
  tool; no per-session limit.** `scripts/locklane register` writes
  `~/.config/systemd/user/locklane-sessions.slice` with `MemoryHigh=65%` and
  `MemoryMax=75%` (systemd takes `%` relative to physical memory; checked on this
  host's systemd 255). The person changes it with `systemctl --user set-property
  locklane-sessions.slice MemoryMax=...`. That writes a drop-in under
  `~/.config/systemd/user.control/`, which takes precedence over the unit file and
  survives `register`/`update` rewriting it, so no new Locklane setting is needed. The
  README documents it. A per-session limit was left out: when the slice is full, the
  kernel's cgroup OOM killer and `systemd-oomd` already act within the slice, and
  oomd picks one session's scope.
  On the 24 October machine (62 GB, about 40 GB held by other services), 75% would
  not by itself have prevented a machine-wide OOM. #1014's scores then make the
  kernel pick a session's process, and the README shows how to set a tighter value.
- **Stop, restart, update and crash still end every session, as today.** A session
  cannot outlive the engine that holds its PTY master (resuming is a non-goal), so:
  - `stop_server` (used by stop, restart, update, register and uninstall) runs a
    blocking `systemctl --user stop locklane-sessions.slice` after the service is
    gone, sweeping anything the engine's own `closeAll` and the descendant kill did
    not reach (a process that left the tree).
  - The unit gets `ExecStopPost=-<systemctl> --user --no-block stop
    locklane-sessions.slice`, so an engine that exits on its own (crash, OOM kill
    under `OOMPolicy=continue`) takes its sessions with it, as the unit's own
    control-group stop used to. `--no-block` so the unit's stop never waits on
    another job from inside itself.
- **The engine still sees a session's exit.** `systemd-run --scope` moves its own
  process into the scope and `exec`s the command, so the PTY's pid is the session's
  process throughout. `PtySessionScopeTest` asserts this (`isAlive()` goes false).
- **Composition with #1014's OOM score:** `PtySession.spawnCommand` builds `/bin/sh`
  (raise score to 500, exec) → `systemd-run ... --slice=locklane-sessions.slice`
  (exec) → the command. The score is raised first, so the scope inherits it. The live
  test reads both the cgroup (slice and `.scope`) and `500` from inside a session.
  As in #1014, a program that does not resolve gets no wrapper at all, so pty4j still
  refuses it.
- **When scoping applies:** `SessionScope` probes once, on the first session. It
  needs `/proc/self/cgroup`, `systemd-run` on `PATH` (used by absolute path), and a
  successful `systemd-run --user --scope --quiet --collect --slice=... true` within
  10 s. Every failure is logged with its cause, and sessions then start as before.
  This keeps CI runners and containers without a user manager working. A session
  whose environment lacks `XDG_RUNTIME_DIR` also gets no scope: found while testing,
  `systemd-run` fails with "Failed to connect to bus" without it, and the engine's
  PTY tests start sessions with an empty environment. Real sessions inherit the
  engine's environment.
- **Slice name `locklane-sessions.slice`:** systemd nests it as
  `locklane.slice/locklane-sessions.slice` (a dash means hierarchy). That is
  harmless, and the cgroup path reads `/locklane-sessions.slice/`.
- **The console-tab refusal (#647) had to learn the slice.** A shell tab is no longer
  in `locklane.service`'s cgroup, so `refuse_inside_server` would have let `locklane
  stop` run from a tab and kill itself mid-stop. It now also matches
  `/locklane-sessions.slice/`, and the control-group file it reads is overridable
  (`LOCKLANE_CGROUP_FILE`) so the harness can test it. The harness's own
  `in_server_cgroup` matches the slice too.

## Deviations / notes
- Lifecycle coverage: `systemd-sessions-slice` (slice file and `ExecStopPost`),
  `systemd-stop-ends-sessions` (stop and restart stop the slice after the service),
  `systemd-update-rewrites-unit` (extended: the slice file is rewritten and the slice
  stopped on update), `systemd-uninstall-removes-slice`, and
  `console-tab-refusal-in-sessions-slice`, which fails without the refusal fix
  (checked). The systemctl stub treats `stop locklane-sessions.slice` as a recorded
  no-op.
- `uninstall` removes the slice unit file but leaves any `set-property` drop-in
  under `~/.config/systemd/user.control/`. That is systemd's own file; noted, not
  removed.
- Not verified here (human-judged): on a real install after update,
  `/proc/<agent pid>/cgroup` shows the slice and scope; `systemctl --user show
  locklane-sessions.slice -p MemoryHigh,MemoryMax` shows the limit; and a session
  pushing the slice past its limit is killed inside it while the engine and other
  sessions survive. `ExecStopPost` behaviour on a real engine crash is likewise
  unexercised: the stub harness does not run unit hooks.

## Agents
- work: claude-code / claude-opus-5-5
