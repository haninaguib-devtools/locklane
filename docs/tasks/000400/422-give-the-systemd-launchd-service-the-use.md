# 422 — Give the systemd/launchd service the user's real PATH so it can find installed CLIs
Issue: #422

## Asked
Installed as the per-user background service (systemd on Linux, launchd on macOS),
locklane can't find the AI CLIs it launches (`claude`, `codex`, `opencode`) even when
they're installed and callable from an ordinary terminal, because the service inherits
whatever minimal `PATH` its service manager hands it rather than the user's login-shell
`PATH`. `install.sh` should resolve the user's real login-shell `PATH` once at install
time and bake it into the systemd unit / launchd plist it generates; `update.sh` should
refresh that same PATH line when it manages an existing service, so re-running it
repairs an already-broken install without a reinstall.

## Done when
- The systemd unit `install.sh` writes contains an `Environment=PATH=...` line, and its
  value is the `PATH` resolved from the user's login shell (e.g. `$SHELL -lc 'echo $PATH'`),
  not `install.sh`'s own possibly non-login environment — `grep Environment= <unit file>`.
- The launchd plist `install.sh` writes contains an `EnvironmentVariables` dict with a
  `PATH` key holding the same resolved value — `grep -A2 EnvironmentVariables <plist file>`.
- `update.sh`, when it finds an existing systemd or launchd service, rewrites that same
  `PATH` line/entry with a freshly-resolved value (not just restarts the service
  unchanged) — re-running `update.sh` on an already-broken install fixes it.
- Human-judged: on a real Linux systemd install and a real macOS launchd install, after
  `install.sh` (or `update.sh` on an existing install) runs, a freshly-started terminal
  session in the app can launch `claude`, `codex`, and `opencode` (whichever are actually
  installed on that machine) even though none of their directories are on the service
  manager's own default `PATH`.

## Explicitly not
No Java changes (`InstalledAgentDetector`, `TerminalWebSocketHandler`,
`InstalledAgentsStore`) — they already read `PATH` correctly; the service's own `PATH`
was the actual defect. No change to the detached (`nohup`) fallback path used when no
service manager is available — it already inherits `install.sh`'s own invoking shell
directly, a separate and pre-existing consideration outside this bug's scope.

## Decisions made along the way
- Resolved the login-shell PATH with `"${SHELL:-/bin/sh}" -lc 'echo $PATH'`, falling
  back to the installer/updater's own `$PATH` if that returns empty — never leaves
  `Environment=PATH=` unset. (implementer, 2026-08-30)
- `update.sh` rewrites an existing unit/plist's PATH in place rather than regenerating
  the whole file, matching the issue's own Done-when wording ("rewrites that same PATH
  line/entry"); for systemd this is a small line-rewrite loop (avoids `sed` escaping
  a PATH value), for launchd it's `/usr/libexec/PlistBuddy`, the standard macOS tool for
  editing a plist. (implementer, 2026-08-30)

## Deviations / notes
- none
