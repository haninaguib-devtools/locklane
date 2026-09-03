# 612 — Install status.sh, start.sh and stop.sh alongside update.sh and uninstall.sh
Issue: #612

## Asked
Someone who installed locklane should be able to check whether the server is running,
stop it, and start it again without knowing which service manager their machine uses
or remembering the commands the installer printed once and that then scrolled away.
Today `install.sh` ends by printing the right `systemctl --user ...` or `launchctl ...`
lines for the mode it picked, and nothing prints them for the detached `nohup` fallback
at all. Add three small scripts next to `update.sh` and `uninstall.sh` —
`~/.locklane/status.sh`, `~/.locklane/start.sh`, `~/.locklane/stop.sh` — that do the
right thing for whichever of the three launch modes the install actually uses:

| Mode | status.sh | stop.sh | start.sh |
|---|---|---|---|
| systemd user service (Linux) | `systemctl --user status locklane` | `systemctl --user stop locklane` | `systemctl --user start locklane` |
| launchd agent (macOS) | `launchctl print gui/$(id -u)/com.locklane.server` | `launchctl bootout gui/$(id -u)/com.locklane.server` | `launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.locklane.server.plist` |
| detached fallback | report the pid from `locklane.pid` and whether it is alive | `kill` that pid and wait for it to exit (the bounded 30s wait `update.sh` already uses) | the same `nohup ... & disown` start `install.sh` and `update.sh` use, writing `locklane.pid` |

Like `uninstall.sh` (#392), they are generated in place by `install.sh` with the
install directory, the service kind and the unit/plist path baked in, and rewritten by
`update.sh` on every run so an install never keeps a stale copy. They reference no
network tool and do not need `gh`. "Remove" is not a fourth script:
`uninstall.sh --service-only` already does exactly that.

The scripts must say in a comment, not silently: on macOS `stop.sh` uses `bootout`
because a KeepAlive agent cannot be stopped any other way, and `bootout` also unloads
it — so a stopped agent does not come back at the next login until `start.sh` (or
`update.sh`) bootstraps it again. On Linux `stop` leaves the unit enabled, so it comes
back at the next boot. `start.sh` after `stop.sh` restores the installed state on both.

## Done when
- `install.sh` writes executable `$INSTALL_DIR/status.sh`, `start.sh` and `stop.sh` on
  every successful install, in all three launch modes, with `$INSTALL_DIR`, the service
  kind and the unit/plist path interpolated in (`printf %q`, as `write_uninstall_script`
  does). `grep -Eq 'gh |curl|wget' status.sh start.sh stop.sh` finds nothing.
- `update.sh` rewrites all three on every run, after it has detected the service kind,
  the same way it rewrites `uninstall.sh`.
- The generator function(s) are duplicated byte for byte between `install.sh` and
  `update.sh` with the same `# end <name>` marker convention as
  `write_uninstall_script`, and the task's checks diff the extracted ranges of the two
  copies.
- `stop.sh` then `start.sh` leaves the server running and reachable on its port in each
  mode; `status.sh` exits 0 when the server is running and non-zero when it is not, in
  each mode, printing something a person can read either way. For the detached
  fallback, `status.sh` treats a missing pid file or a dead pid as "not running" (exit
  non-zero), never as an error.
- `stop.sh` on an already-stopped server, and `start.sh` on an already-running one,
  exit 0 with a message rather than failing or starting a second copy (for the
  fallback: an alive pid in `locklane.pid` means already running).
- The post-install summary in `install.sh` lists the three new scripts and its per-mode
  "Status / Stop / Remove" hint block is replaced by (or points at) them; `README.md`'s
  list of what lives in `~/.locklane` and the site's install section
  (`site/index.html`) mention them alongside `update.sh` and `uninstall.sh`.
- `bash -n` passes on `install.sh`, `update.sh`, and on each generated script for each
  of the three modes.

## Explicitly not
- No `restart.sh`: `stop.sh && start.sh` is the restart, and the README says so.
- `update.sh` keeps its own stop/start code; delegating it to the generated scripts was
  allowed but not required, and the plan chose the smaller change.
- No change to `uninstall.sh`'s behaviour.

## Decisions made along the way
- One generator, `write_control_scripts <dir> <kind> <reg>`, writes all three scripts
  from a single loop, closed by `} # end write_control_scripts` — the same marker
  convention as `write_uninstall_script`, for the same reason: the generated bodies
  contain a column-0 `}` of their own (agent, 2026-09-02, per the plan).
- The fallback `start.sh` resolves `java` from `PATH` at run time (erroring plainly if
  it is missing) rather than baking in the path `install.sh` saw, matching what
  `update.sh`'s own relaunch line does (agent, 2026-09-02, per the plan).
- On launchd, `status.sh` captures `launchctl print` and looks for `state = running`
  instead of dumping the whole listing, so the exit code reflects whether the server
  process is up and not only whether the agent is loaded; `stop.sh` and `start.sh`
  probe `launchctl print` first so that `bootout` on an unloaded agent and `bootstrap`
  on a loaded one become a message and exit 0 (agent, 2026-09-02).
- `update.sh` generates the three scripts in its final refresh block, next to the
  uninstaller, after the service kind is detected (agent, 2026-09-02, per the plan).

## Deviations / notes
- The systemd and launchd modes were not exercised end to end by the agent: the only
  systemd `locklane` user unit on this host runs the console session doing the work,
  and there is no macOS host. Both are the plan's human checks. The detached fallback
  was exercised with a stand-in `java` on `PATH`.
