# 392 — Add an uninstaller that removes the service, and optionally all of ~/.locklane
Issue: #392

## Asked
Someone who installed locklane can undo it cleanly, without needing to know what a
systemd unit or a launchd agent is, and without accidentally destroying their data.
`install.sh` sets up a per-user service whose files live outside `~/.locklane`
(`~/.config/systemd/user/locklane.service` on Linux,
`~/Library/LaunchAgents/com.locklane.server.plist` on macOS), so deleting the install
directory by hand leaves a service manager trying to restart a jar that is no longer
there, in a restart loop. This adds an uninstaller that always takes the service down and
de-registers it properly, and then asks separately whether the install directory — which
holds the accounts, the projects, and the database — should also be deleted.

The uninstaller is generated in place by `install.sh` (a heredoc, with the install path,
the unit/plist path, and the detected service kind already baked in) rather than
downloaded the way `update.sh` is. An uninstaller that needs the network, a working `gh`
login, and the repository to still exist is unavailable exactly when it is most wanted.

## Done when
- `install.sh` writes an executable `$INSTALL_DIR/uninstall.sh` on every successful
  install, in all three launch modes (systemd user service, launchd agent, and the
  detached `nohup` fallback), with `$INSTALL_DIR`, the unit/plist path, and the service
  kind interpolated into it. It references no network tool:
  `grep -Eq 'gh |curl|wget' uninstall.sh` finds nothing.
- Running `uninstall.sh` always, in every mode, (a) stops the server and (b) removes its
  registration: `systemctl --user disable --now locklane`, delete the unit,
  `systemctl --user daemon-reload`; or
  `launchctl bootout gui/$(id -u)/com.locklane.server` and delete the plist; or kill the
  pid in `locklane.pid` and wait for it to exit (the same bounded wait `update.sh` uses,
  and a pid that is already gone is not an error). Afterwards no unit or plist file
  remains anywhere.
- It then asks whether to also delete the install directory, in wording that says plainly
  what is lost (login accounts, projects, database) rather than only naming a path.
  Deleting requires a typed confirmation, not a single keystroke.
- Prompts read from `/dev/tty`, following #354, so the script works when piped.
- Non-interactive flags cover both answers and skip the prompt: one that removes only the
  service, one that removes everything, and one that confirms without asking. With no flag
  and no tty, it exits non-zero with a message rather than guessing — in particular it
  never defaults to deleting.
- The `rm -rf` is guarded: it refuses to run on an empty path, on `/`, or on `$HOME`, and
  is the last statement in the script (it deletes the script's own file).
- `enable-linger` is left as it was, and `gh` and `java` are not touched; the closing
  message says so.
- `update.sh` rewrites the generated `uninstall.sh` so an existing install does not keep a
  stale copy.
- `install.sh`'s closing message and `README.md` point at `~/.locklane/uninstall.sh`.
- Human judgment: an end-to-end run on a real install — install, uninstall service-only,
  confirm the server is stopped and the unit gone while the data survives; then a second
  run choosing full removal.

## Explicitly not
- No uninstall entry point in the application UI or the engine — this is a shell script
  only.
- No Windows support; `install.sh` has none today either.
- No backup or export of the database before deletion. The typed confirmation is the whole
  of the safety net here.
- Does not change how `install.sh` chooses or configures the service itself (#385, #386).

## Decisions made along the way
- The generation lives in one shell function, `write_uninstall_script <dir> <kind>
  <unit-or-plist-path>`, carried byte-for-byte identically in both `install.sh` and
  `update.sh`. `update.sh` is downloaded standalone at install time and cannot source
  anything from `install.sh`, so the duplication is unavoidable; making it an exact
  duplicate of a named function makes the drift machine-detectable (a `diff` of the two
  extracted ranges) instead of a comment nobody rereads. The same duplication already
  exists in these two scripts for service-kind detection (#386).
- The function's closing line is `} # end write_uninstall_script`, not a bare `}`. The
  generated script contains a `}` at column 0 of its own (its `usage()` function), so an
  extraction keyed on `^}$` stops early and yields an unparseable fragment — found by the
  check itself failing. The end marker is what the drift check keys off.
- The generated script is written by two heredocs, not one: an expanded one carrying only
  the three baked-in values (each through `printf %q`, so a path with a space or a quote
  cannot break the result), then a quoted one carrying the whole runtime body verbatim.
  One expanded heredoc would mean escaping every runtime `$` and getting one wrong would
  silently produce an uninstaller with an empty path in its `rm -rf`.
- The uninstaller sweeps both well-known registration paths — the systemd unit and the
  launchd plist — not only the one baked in. An install that set up a unit and later fell
  back to the detached start would otherwise leave the first one behind, which is exactly
  the restart loop the task exists to prevent.
- `--all` selects deletion but still asks for the typed confirmation; `--yes` is what
  skips the asking. So `--all` alone on a machine with no terminal exits non-zero rather
  than deleting — the issue's "never defaults to deleting" holds for every combination,
  not just for the no-flag case.

## Deviations / notes
- The plan's drift check was written as `sed -n '/^write_uninstall_script()/,/^}$/p'`.
  The actual check uses `/^} # end write_uninstall_script$/` as its end address, for the
  reason in the decisions above. Same check, corrected address.
- Two defects found by the checks and fixed, both worth remembering:
  - `[ ! -r /dev/tty ]` is not a test for "there is a terminal". In a session with no
    controlling terminal the device node still exists and is mode-readable, so the test
    passed, the guard did not fire, and the subsequent `read` failed into an empty answer.
    The data survived (the empty answer is not the word `delete`), but the script exited 0
    having guessed, which the issue forbids. The probe is now `! : < /dev/tty 2>/dev/null`
    — actually opening it is the only honest test.
  - The closing message originally read "gh and java are untouched", which made the
    issue's own acceptance check, `grep -Eq 'gh |curl|wget' uninstall.sh`, match on the
    message rather than on a real dependency. Reworded to "the GitHub CLI and java", which
    satisfies both criteria instead of trading one against the other.
- Checks are run from a scratch harness rather than a committed script: the plan's Allowed
  paths cover three files and this record, and adding a test script to the repository is
  not in them. The commands are written out in the plan's Validation section so a reviewer
  can rerun them.
- Fix pass after the cold review on PR #398, answering its **high finding 1**: the
  `rm -rf` guards compared raw strings, so they refused `$HOME` but not `$HOME/`. One
  trailing slash was not string-equal to `$HOME`, walked past the check, and would have
  emptied the home directory; the reviewer reproduced it against a throwaway `HOME`. The
  same hole existed for `/` via `//` and `/.`. The guards now resolve both the target and
  `$HOME` with `cd … && pwd -P` before comparing, so the refusal is about the directory
  rather than about how it was spelled, and the resolved path is the only one used from
  that point on. `write_uninstall_script` also resolves the path it bakes in, so a
  spelling never reaches the generated script in the first place.

  My own earlier behavioural tests could not have caught this: they passed the guards the
  exact strings the guards test for. The review's fuzzing of alternative spellings is what
  found it.
- The review's **medium finding 2** (a relative `LOCKLANE_HOME` deleting whatever that
  name means in the caller's working directory) has the same root cause and is closed by
  the same generation-time `pwd -P`, not by a separate decision to take a medium finding.
  Verified: generating with a relative `inst` now bakes the absolute path.
- Rewording during the fix: a comment containing the word "through" made the issue's own
  acceptance check, `grep -Eq 'gh |curl|wget' uninstall.sh`, match on the letters `gh `
  inside `throuGH And`. Reworded. This is the second time that check has matched on prose
  rather than on a dependency, which is worth knowing about the check itself.
- Left unaddressed, as medium/low findings the human has not asked for by number: the
  review's finding 3 (a pid file containing `0` makes `kill "$old_pid"` signal the whole
  process group — a pattern inherited from `update.sh`, #385, which the reviewer
  recommended as its own issue), finding 4 (the no-tty probe leaks a raw `/dev/tty` error
  line before its friendly message), and finding 5 (the sweep deletes the systemd unit in
  non-systemd modes without `disable`/`daemon-reload`, leaving a dangling
  `default.target.wants` symlink).
- Not verified here, and left to the human check the issue asks for: the behaviour against
  a real systemd unit or launchd agent. The `systemctl`/`launchctl` branches were checked
  for generation, interpolation, and syntax only — nothing in this session stopped a real
  service.
