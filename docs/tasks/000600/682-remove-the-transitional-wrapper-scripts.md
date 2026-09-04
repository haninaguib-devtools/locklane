# 682 — Remove the transitional wrapper scripts and close two low notes from the control-program review
Issue: #682

## Asked
Once the control program (#678, `~/.locklane/locklane`) has been on every existing
installation for a while, the five transitional wrapper files it still writes next to
itself — `status.sh`, `start.sh`, `stop.sh`, `uninstall.sh`, `update.sh` — are noise:
each is a three-line `exec` of `locklane <subcommand>`, kept only so an install made
before #678 could migrate through its own self-refreshing `update.sh` (#647). Remove
them so `locklane <subcommand>` is the only interface. Timing is the human's call, not a
tracker blocker: this is done only after the person confirms their installations have
been upgraded past v0.2.10; the `update.sh` release asset itself stays in the repository
and on releases, it just is no longer written into `~/.locklane`.

Two low notes from #680's cold review ride along, both in the same file or the same area:

1. `locklane uninstall` with no registration it can find says "stopped" without
   checking, when `service.env`, the unit and the plist are all absent (and no
   `locklane.pid`) but the service manager still has the label loaded/active — the
   shape a botched manual cleanup leaves. `uninstall` must probe both service managers
   directly before printing anything, and run the verified stop if either says loaded.
2. `CodeServerService.stop(consoleId)` blocks the session-close listener for up to 7s
   when code-server ignores SIGTERM. `stop(consoleId)` should return promptly (hand the
   tree to a background thread), while `stopAll()` at shutdown stays synchronous.

## Done when
- `scripts/locklane` writes no `*.sh` file: `grep -c 'write_wrappers\|Transitional
  wrapper (#678)' scripts/locklane` prints 0. `register` and `update` delete any of the
  five names present in the install directory only when the file carries the
  `Transitional wrapper (#678)` marker or is byte-identical to the released
  `update.sh` — a file with any other content is left alone and named in the output.
- `update.sh` in the repository still exists and is still uploaded by
  `.github/workflows/release.yml`; it still fetches `locklane` when absent and execs
  `locklane update`. After that migration completes, the install directory holds no
  `update.sh`.
- `locklane install`'s closing summary and `locklane help` no longer mention the
  wrappers; README's Installing section drops the "five scripts … transitional"
  paragraph and names only `locklane <subcommand>` and the install one-liner.
- Harness (`bash scripts/tests/lifecycle/run.sh`) passes with: the `wrappers` scenario
  replaced by `no-wrappers` (after `register`, none of the five names exists);
  `migrate-old-layout` asserting the five generated files and the migrated-from
  `update.sh` are gone afterwards and `locklane` is present; a new `foreign-file-kept`
  scenario (a `stop.sh` with unrelated content survives `register` and is named in the
  output).
- Harness scenario `uninstall-unregistered-but-loaded`: no `service.env`, no plist, no
  unit, stub launchd agent loaded with a live pid → `uninstall --all --yes` runs the
  verified stop (pid gone, agent booted out) and then deletes; with the stub `kill`
  swallowing SIGKILL it exits non-zero, the directory is untouched, and the output
  never contains "stopped". The same with the stub systemd agent active.
- `./mvnw -B test` passes with a new `CodeServerServiceTest` case: a runner spawning
  `sh -c 'trap "" TERM; sleep 300 & wait'`; `registry.close(id)` returns within 1s
  (measured), and the spawned process and its child are gone within 10s.
- `./.t-workflow/scripts/consistency-check.sh` passes.
- Human, before starting: confirm every installation you care about is on v0.2.10 or
  later (`~/.locklane/service.env` exists and `~/.locklane/locklane help` works).

## Explicitly not
- Removing `update.sh` from the repository or from the release assets: it remains the
  migration entry for any install still on the pre-#678 layout.
- Removing `install.sh` or changing the install one-liner.
- The two record-wording notes from #680's review (the `closeAll()` ordering, the
  Goal-vs-plan wording of the install.sh deviation): the record is merged and those are
  documentation, not work.
- Any change to the stop routine itself beyond the unregistered-but-loaded detection
  above.

## Decisions made along the way
- The four small wrappers are recognized for deletion by their one load-bearing line
  (`exec "$(dirname "$0")/locklane" <name> "$@"`), not by scanning for the
  `Transitional wrapper (#678)` comment text — the done-when's own `grep -c` check
  requires that literal string to be absent from `scripts/locklane` entirely, so
  detection has to be structural. `update.sh` is recognized by byte content against a
  copy of the released `update.sh` embedded in `scripts/locklane` (`/t-drive`, from
  `/t-plan`'s report, 2026-09-04).
- `scripts/tests/lifecycle/run.sh`'s `old_layout()` fixture is updated to write the
  four small scripts with the real marker content a #678-installed host actually has on
  disk (rather than the pre-#678 `echo old` placeholder it used before), since the
  `migrate-old-layout` scenario now also has to exercise marker-based deletion in the
  same run (`/t-drive`, from `/t-plan`'s report, 2026-09-04).

## Deviations / notes
- none
