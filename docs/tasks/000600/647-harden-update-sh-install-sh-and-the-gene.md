# 647 — Harden update.sh, install.sh and the generated control scripts
Issue: #647

## Asked
Make `update.sh`, `install.sh` and the generated `start.sh` / `stop.sh` / `status.sh` /
`uninstall.sh` stop and restart the server reliably, and stop looking as if they had
failed when they had not. An investigation on 2026-09-03 found six ways a person running
`update.sh` ends up with the server left down, or believes the old server never stopped:

1. Run from a Locklane console tab, the scripts kill themselves — the console shell is
   a child of the server JVM inside the `locklane.service` cgroup (KillMode
   control-group), so `systemctl --user stop locklane` SIGTERMs the script at its own
   stop step and nothing restarts the server (an explicit stop suppresses
   `Restart=always`). The launchd agent has the same shape on macOS.
2. On macOS, `update.sh` and `stop.sh`/`start.sh` race launchd: `bootout` returns before
   the JVM has exited and the immediate `bootstrap` fails ("Bootstrap failed: 5:
   Input/output error"), leaving the server down under `set -e`.
3. Every stop is logged as a failure: the JVM exits 143 on SIGTERM and the unit lacks
   `SuccessExitStatus=143`, so `status.sh` after `stop.sh` shows `failed`.
4. `update.sh` never refreshes itself: it downloads only the jar, and `install.sh` fetched
   it once from `main`, so an install made before a change to `update.sh` (e.g. #628's
   code-server block) never gets it.
5. `update.sh` downloads after stopping: a failed or truncated download leaves the server
   down.
6. Re-running `install.sh` over a running install clobbers the jar under the running JVM
   and never stops it (`enable --now` is a no-op on an active unit).

## Done when
- `update.sh`, `stop.sh` and `uninstall.sh` refuse to run from inside the server's own
  process tree (systemd: `locklane.service` in `/proc/self/cgroup`; launchd: the agent's
  pid is an ancestor of the shell), telling the person to use ssh or a local terminal,
  exit non-zero before anything is stopped. `start.sh` and `status.sh` carry no guard.
- The launchd path in `update.sh` and the generated `stop.sh` waits, bounded to 30 s,
  for the agent to be gone after `bootout` before any download/`bootstrap`; `bootout`
  failures other than "not loaded" are shown, not silenced.
- The systemd unit written by `install.sh` carries `SuccessExitStatus=143`; `update.sh`
  adds the line to an existing unit that lacks it, exactly once. After `stop.sh`,
  `systemctl --user status locklane` reports `inactive (dead)`, not `failed`.
- `update.sh` fetches the newest `update.sh` alongside the jar and, if it differs,
  replaces itself and re-executes once (loop-guarded), before any stop. Both scripts
  fetch `update.sh` from the release channel (an asset attached by
  `.github/workflows/release.yml`); a release without that asset is not an error —
  the installed copy (or, for `install.sh`, the raw-`main` copy) is used.
- `update.sh` downloads the jar to a temporary path, verifies it is a readable zip, and
  only then stops, swaps and restarts; a failed download leaves the running server
  untouched and exits non-zero.
- `update.sh` prints the release tag it installed.
- `install.sh` over an existing install stops the running service before the jar is
  swapped and lets its service-setup block bring it back, so the server that is up
  afterwards runs the jar just downloaded.
- The duplicated functions in `install.sh` and `update.sh` stay byte-identical.
- `README.md`'s script list says the scripts run from a terminal outside Locklane's own
  console and what `update.sh` now does.
- Detached fallback and the guard exercised with generated copies in a scratch
  directory; the systemd path on the reference host and launchd on a Mac are the
  plan's human checks (post-merge, from ssh — the implementing session runs inside the
  service and must not stop it).

## Explicitly not
- Changing how the engine spawns console shells (e.g. moving them out of the service
  cgroup with `systemd-run --scope`); the scripts refuse instead.
- Any change to the jar, the engine, or the client; the in-app update banner is
  untouched.
- Rotating or renaming `locklane.log` (the detached fallback's `>` truncation vs. the
  unit's `append:` is left as is).

## Decisions made along the way
- The plan's Allowed paths hold, but its claim that none is a protected surface was
  wrong: `.t-workflow/scripts/protected-paths.sh --stdin` reports
  `.github/workflows/release.yml` (all of `.github/`) and `README.md` as protected. The
  plan gate passes because the `## Plan` section exists and covers both; the
  consequence is that `/t-review` is required before `/t-ship`, not optional as the plan
  said (agent, 2026-09-03).
- Shared behaviour new to both scripts — the console guard, the launchd stop-and-wait,
  stopping whatever is running, downloading and verifying the jar — lives in four new
  top-level functions (`refuse_inside_server`, `stop_launchd_agent`,
  `stop_running_server`, `download_jar`), duplicated byte for byte in `install.sh` and
  `update.sh` under the same `} # end <name>` marker convention as the generators, so
  the existing extract-and-diff check covers them (agent, 2026-09-03).
- The generated `stop.sh` and `uninstall.sh` cannot source those helpers, so they carry
  their own copy of the guard, emitted by a new generator `write_inside_server_guard`
  (itself duplicated across the two scripts), and `stop.sh`'s launchd branch inlines the
  same bootout-and-wait logic. Two copies of the guard text per script — the top-level
  function and the emitted one — was chosen over `eval`-ing one heredoc into both roles,
  for readability (agent, 2026-09-03).
- The console guard exits 2, distinct from the 1 the scripts use for a stop that timed
  out, so a caller can tell "refused to start" from "tried and failed" (agent,
  2026-09-03).
- `install.sh` stops a running instance after the prompts and just before the seeding
  run, not at the top: the server stays up while a person types answers, and the seed
  then runs against a database no other JVM holds open (agent, 2026-09-03).
- The self-refresh's "no asset" message is worded to cover a fetch failure too ("no
  update.sh on the newest release, or it could not be fetched"), since the two are not
  told apart and neither should stop the update (agent, 2026-09-03).

- The guard text the generators emit ends with the same `} # end refuse_inside_server`
  marker line as the top-level function, so the extract-and-diff check sees two
  bounded, identical ranges per script instead of one range running from the embedded
  copy into `resolve_login_path`'s (legitimately different) comment (agent, 2026-09-03).

## Deviations / notes
- The branch was created from `origin/main` (803480f) rather than the local `main`,
  which is checked out in the primary worktree at 4b0c1b6 and cannot be fast-forwarded
  from here; the two are behind-only, no divergence (agent, 2026-09-03).
- The plan named `gh release view --json tagName` as the unauthenticated source of the
  installed tag. It is not unauthenticated: with no login and no `GH_TOKEN`, gh exits 4
  asking for `gh auth login` (verified in a scratch `HOME`), while `gh release download`
  still works. `update.sh` tries gh first and falls back to an unauthenticated
  `curl` of the REST `releases/latest` endpoint, parsed with `sed`; both failing prints
  "(version unknown)" and does not fail the run. Verified from the scratch `HOME` to
  print `v0.2.5` (agent, 2026-09-03).
- `site/index.html` was left unchanged: its two script paragraphs stay accurate and
  say nothing about where the scripts are run from; the README carries the new rule.
- Checks run against generated copies in the scratch directory, never `~/.locklane`:
  the systemd `stop.sh` and `uninstall.sh` and `update.sh` itself all refused from this
  console session with exit 2 and a stand-in `systemctl` never invoked; the detached
  fallback lifecycle (status 1 → start 0 → status 0 → start-again 0 → stop 0, pid file
  removed → stop-again 0) with a stand-in `java`; a full `update.sh` run in a scratch
  `HOME` (fallback mode, real 48 MB download, `unzip -tq` pass, stand-in `java` and
  `curl`) printed the kept-copy line for the missing asset, swapped the jar, relaunched,
  regenerated the four scripts with the guard only in `stop.sh` and `uninstall.sh`;
  `download_jar` against a repo with no matching asset and against a stand-in `gh`
  writing garbage both exited 1 leaving no `locklane.jar.new`; the
  `update_systemd_success_status` pass on a copy of the live unit added the line once
  and a second run added nothing (agent, 2026-09-03).
- Not exercised here: the systemd and launchd stop/start paths end to end, and
  `install.sh` over a running install — the plan's post-merge human checks from ssh
  and on a Mac.
