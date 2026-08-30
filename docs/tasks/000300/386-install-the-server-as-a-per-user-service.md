# 386 — Install the server as a per-user service that survives reboot and crash
Issue: #386

## Asked
Once the installer starts the server detached (#385), it survives the terminal that
launched it but nothing else: it does not come back after the machine reboots, and it
does not come back if the JVM crashes or is killed. Install the server as a real
per-user service on the host — a systemd user unit on Linux, a launchd agent on macOS —
so it starts at login or boot and restarts on its own after a crash, deferred by #289
and #385 as its own task.

## Done when
- On Linux, installing leaves a systemd user unit that starts locklane at login and
  restarts it after the process is killed — verified by `systemctl --user status`, then
  killing the JVM and seeing the port answer again without human action.
- On macOS, installing leaves a launchd agent with the equivalent behaviour — verified by
  `launchctl print`, then killing the JVM and seeing the port answer again.
- Installing on a platform with neither falls back to the detached start from #385 and
  says so plainly, rather than failing.
- Service output is still reachable as a log, on both platforms.
- There is a documented way to stop the service and to remove it, and removing it leaves
  no unit or agent behind.
- `update.sh` restarts the service rather than starting a competing copy alongside it.
- `./.t-workflow/scripts/consistency-check.sh` passes.
- Human judgment: reboot the machine and confirm the app is answering without anyone
  starting it.

## Explicitly not
- No system-wide (root) service — a per-user service only, matching where the install
  already lives, under `~/.locklane`.
- No Windows service.
- No monitoring, alerting, or health-check endpoint beyond what restart-on-crash needs.

## Decisions made along the way
- Unit/agent generated inline in `install.sh` via heredoc (same technique already used
  for `application-locklane.properties`), rather than as separate template files fetched
  from the repo — one fewer network call and no substitution step, and the scope note
  ("whatever unit/agent template files the two platforms need") only anticipates such
  files, it does not require them.
- Linux: systemd user unit at `~/.config/systemd/user/locklane.service`, `Type=simple`,
  `Restart=always` (not `on-failure` — a plain `kill` exits non-zero from the shell's
  point of view but the Done-when's "kill the JVM" test must restart it regardless of
  exit status), `WantedBy=default.target`, stdout/stderr appended to the existing
  `locklane.log`. `systemctl --user enable --now` starts it immediately and at future
  logins; `loginctl enable-linger "$USER"` (best-effort, failure ignored) is added so the
  service also comes up on a headless boot with no interactive login, matching the
  issue's "starts at login or boot" goal and the human-judgment reboot test — the
  Done-when's own systemd bullet only requires "at login", linger is the extra needed for
  a server actually left running headless.
- macOS: launchd agent at `~/Library/LaunchAgents/com.locklane.server.plist`,
  `RunAtLoad=true`, `KeepAlive={SuccessfulExit=false}` (restarts on any non-clean exit,
  which a `kill` produces), stdout/stderr to the same `locklane.log`. Per-user
  LaunchAgents only start at login (the boot-without-login case would need a root
  LaunchDaemon, excluded by this task's own Non-goals) — accepted as the ceiling for a
  per-user macOS service, documented in the install output.
- Neither service manager found (or `systemctl`/`launchctl` set-up fails, e.g. no D-Bus
  session) → falls back to #385's plain detached `nohup`/`disown` start, with a printed
  message saying restart-on-crash and restart-on-reboot are not available.
- `update.sh` detects which mode is installed by checking for the unit/plist file, and
  for a managed service stops it (`systemctl --user stop` / `launchctl bootout`) before
  downloading the new jar and starts it again afterward (`systemctl --user start` /
  `launchctl bootstrap`) — same stop-before-download order #385 established, so the
  running JVM never has the jar swapped out from under it. The pid-file path from #385
  is kept unchanged for hosts with no service manager.
- Stop/remove instructions are printed at the end of `install.sh`'s summary (the same
  place the script already documents `update.sh`), not a separate uninstall script — the
  issue's scope names `install.sh`, `update.sh`, and unit/agent templates only.

## Deviations / notes
- none
