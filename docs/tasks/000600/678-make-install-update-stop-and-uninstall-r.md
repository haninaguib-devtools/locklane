# 678 — Make install, update, stop and uninstall reliable with one downloaded control program
Issue: #678

## Asked
Stopping, updating and uninstalling locklane must actually do what they say, on macOS
as much as on Linux. On macOS (v0.2.9) `uninstall.sh --all` deleted `~/.locklane` while
the server was still running, and launchd recreated `locklane.log` afterwards. The
cause was structural: two ~1000-line scripts sharing ~600 duplicated lines, four
generated scripts written as nested heredocs with the service kind baked in, "stop the
server" implemented five times with different rules (the uninstaller's launchd path ran
`launchctl bootout … 2>/dev/null || true`, waited zero seconds, and printed "stopped"
unconditionally), no engine teardown of code-server or console descendants (hidden on
Linux by systemd's cgroup kill), and no test for any of it in the repository.

Replace the arrangement with one control program — `scripts/locklane`, shipped as the
release asset `locklane`, installed as `~/.locklane/locklane`, downloaded never
generated — owning `status`, `start`, `stop`, `restart`, `update`, `uninstall`,
`register` and `install`. It reads the service kind from `service.env` (written at
registration, derived from the unit/plist for older installs) and has exactly one stop
routine: signal, bounded wait, SIGKILL, kill surviving descendants, verify, report.
`uninstall` refuses to delete anything while a server process is alive. `install.sh`
shrinks to a bootstrapper, `update.sh` to the wrapper/migration asset. The engine tears
down code-server and every PTY session's descendants at shutdown. A committed harness
with stub `launchctl`, `systemctl`, `gh`, `java`, `curl` and `kill` runs in CI.

## Done when
- No duplicated lifecycle code: `grep -c 'DUPLICATED, BYTE FOR BYTE' install.sh update.sh`
  prints 0 for both; `write_uninstall_script`, `write_control_scripts` and
  `write_inside_server_guard` exist in neither.
- The control program is a release asset (`release.yml` uploads it next to
  `locklane.jar`), and `install.sh` downloads it from the same release (raw-file fallback).
- After install, `status.sh`, `start.sh`, `stop.sh`, `update.sh`, `uninstall.sh` are
  wrappers that exec the control program with the matching subcommand.
- Stop escalates and verifies on every kind the harness covers (stub agent that keeps
  reporting the service plus a pid that ignores SIGTERM → exit 0, pid gone, "forced").
- Stop reports honestly when it cannot finish (stub kill that pretends → exit 1, names
  the pid, the agent/unit state, the surviving children; never claims "stopped").
- Uninstall never deletes a live install (same case → non-zero, directory byte-for-byte
  untouched, says the server is still running); the clean case removes the directory,
  the plist/unit and the state file, boots the service out, leaves no pid.
- The console-tab refusal is preserved: exit 2, first line "not run", names the console
  tab and the IDE terminal.
- Engine shutdown owns its children: `./mvnw -B test` passes with new tests proving
  `CodeServerService` destroys every code-server tree and `SessionRegistry.closeAll()`
  ends every PTY session's descendants within a bounded deadline.
- The harness runs in CI on every PR (`ci.yml`), and passes on `main`.
- README's operating section describes the control program; `consistency-check.sh` passes.
- Human check, macOS only: on an installed laptop, open a console tab and its IDE, run
  `~/.locklane/uninstall.sh --all` from Terminal.app; afterwards no `locklane.jar` or
  `code-server` process, `launchctl print` reports the agent not loaded, `~/.locklane`
  gone.

## Explicitly not
- Whether the no-service-manager (detached nohup) fallback survives was left to the
  plan — decided: dropped (see Decisions).
- A `--reconfigure` path for port/origins/account (#675's non-goal stands).
- PATH resolution for the service (#672, shipped) and the engine's gh error wording
  (#671, shipped).
- The in-app update banner and the release process itself beyond adding the new asset.
- Any engine shutdown change beyond tearing down child processes.

## Decisions made along the way
- **The detached fallback is dropped** (plan, Claude, 2026-09-04): `install` refuses on a
  host with neither `systemctl` nor `launchctl`; `update`, `stop` and `uninstall` on an
  old detached install (a `locklane.pid`, no unit/plist) refuse *before* touching
  anything and say how to move over by hand.
- **`install.sh` hands the whole install to `locklane install`**, not just the service
  step (Claude, 2026-09-04): the plan had `install.sh` keep the prompts, properties and
  seed and call `locklane register`; keeping those in `install.sh` would have meant
  duplicating `download_jar`, the code-server install and the refusal guard — the very
  duplication the task removes. `install.sh` is now ~40 lines: prerequisites, fetch the
  control program, `exec locklane install < /dev/null`.
- **`update.sh` is the wrapper and the migration asset in one file**: the text `locklane`
  writes as the `update.sh` wrapper is byte-identical to the repository's `update.sh`
  (the released asset), which downloads the control program when the directory has
  none and then execs `locklane update`. The harness checks the two are identical. It
  is ~18 lines, not three: the plan's "≤ 4 lines" applies to the other four wrappers.
- **Signals go through the external `kill`** (`env kill`), liveness through the builtin
  `kill -0`: the harness's stub `kill` can then pretend a SIGKILL succeeded (the one
  "cannot finish" shape a real kill can never produce), while the liveness checks that
  decide what is reported can never be fooled. #676's record used a zombie for this;
  a zombie has no descendants to report, so the stub is the more honest fixture here.
- **The harness re-execs itself under `systemd-run --user --scope`** when it finds itself
  inside the `locklane.service` cgroup (a Locklane console on Linux): the control
  program's own refusal would otherwise, correctly, refuse every systemd stop. Where
  that is impossible the systemd scenarios are skipped, saying so; CI runs outside any
  such cgroup.
- **Grace periods are overridable** (`LOCKLANE_STOP_GRACE`, `LOCKLANE_FORCE_WAIT`,
  defaults 30s and 10s) so the harness does not sit through real timeouts.
- **`register` is the restart-with-fresh-PATH**: it stops a loaded service (verified),
  rewrites the unit/plist with the current login PATH, records `service.env`, writes the
  wrappers and starts. `update` calls it after swapping the jar, which is also how an
  old install gains `service.env` and loses its generated scripts.

## Deviations / notes
- #676 shipped as #679 while this issue was being opened; its escalation and refusal
  wording are carried into `stop_server` rather than kept in a generated `stop.sh`.
- `.github/workflows/ci.yml` is template-owned: the harness step sits inside the
  trailing `# <!-- local -->` slot of the `checks` job, and nothing outside it changed.
