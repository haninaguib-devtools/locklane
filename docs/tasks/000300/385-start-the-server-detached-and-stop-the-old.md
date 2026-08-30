# 385 — Start the server detached, and stop the old instance before updating
Issue: #385

## Asked
Nothing in the install path should leave a server that outlives the terminal that
started it. `install.sh` currently ends by telling the user to run the jar in the
foreground, so it dies when that shell closes. `update.sh` `exec`s the jar in the
foreground too, and never stops the instance already running before it overwrites
`locklane.jar` with `--clobber` — so a live update leaves the old server damaged (its
jar file replaced out from under it) and the new one not running at all, because the
new process fails to bind a port the old one still holds. Start the server detached in
both scripts, with its output going to a log file in the install directory and its pid
recorded there, and make `update.sh` stop the running instance before it replaces the
jar.

## Done when
- `install.sh` leaves a running, detached server: after the script returns and its
  terminal is closed, the chosen port still answers.
- Server output goes to a log file inside the install directory rather than to the
  invoking terminal, and the running instance's process id is recorded there so
  `update.sh` can find it.
- `update.sh` run against a live install stops the running instance, replaces
  `locklane.jar`, and starts the new build detached — afterwards the port answers, serves
  the new build, and exactly one server process is running.
- `update.sh` run when nothing is running still replaces the jar and starts the new build
  detached, rather than failing on the missing process.
- Neither script leaves the user's shell blocked on the server.
- Beyond what #384 specifies, neither script modifies `application-locklane.properties`.
- `./.t-workflow/scripts/consistency-check.sh` passes.
- Human judgment: install, close the terminal, confirm the app still answers; then run
  `update.sh` and confirm the same.

## Explicitly not
- No process supervision: no systemd or launchd unit, no restart-on-crash, no
  run-on-boot (kept deferred per #289).
- No log rotation or log size limit.
- No change to what the installer prompts for or what it writes into
  `application-locklane.properties`.

## Decisions made along the way
- Detached start: `nohup java -jar locklane.jar > "$INSTALL_DIR/locklane.log" 2>&1 <
  /dev/null & disown`, pid captured from `$!` into `$INSTALL_DIR/locklane.pid`. Both
  `nohup` (ignores SIGHUP) and `disown` (drops shell job-table tracking) so the process
  survives the invoking terminal closing under either mechanism.
- `update.sh` stop step: read the pid file, `kill` that pid if it is still alive, wait
  (poll, bounded) for the process to actually exit before the `--clobber` download —
  the ordering bug the issue calls out (jar overwritten while the old JVM still holds
  it open). Missing pid file or a pid that is not running is not an error — proceeds
  straight to the download, per the "nothing running" Done-when.
- Log/pid file names: `locklane.log` / `locklane.pid` in `$INSTALL_DIR`, alongside
  `locklane.jar` and `application-locklane.properties` — no prior convention existed to
  follow.

## Deviations / notes
- none
