# 675 — Make the install one-liner behave as an update when locklane is already installed
Issue: #675

## Asked
Re-running `curl -fsSL .../install.sh | bash` on a machine that already has locklane
installed must do what `update.sh` does — pull the newest jar, refresh the service's
PATH, restart the server, refresh code-server and the control scripts — and nothing
else. Today it half-updates (#647) but also re-asks the port, allowed origins, username
and password, truncates and rewrites `application-locklane.properties` (dropping any
setting the person edited and silently changing the port if they answer differently),
and runs the account seed, which exits 3 after the person typed a password for
nothing. A person who reaches for the one-liner because it is the command they
remember must not lose their configuration for it.

`install.sh` detects an existing install, says so up front, fetches the newest
release's `update.sh` into `$INSTALL_DIR` the way it already does, and hands the run
over to it (`exec`), so the two paths cannot drift. The fresh-install path is unchanged.
Changing the port or origins of an existing install stays a manual edit of the
properties file plus `stop.sh`/`start.sh`, and the hand-over message says so.

## Done when
- With an existing install present, `install.sh` prompts for nothing, leaves
  `application-locklane.properties` byte-identical, does not run the seed
  (`install-seed.log` is never created), and exits 0 with the newest jar running — the
  same end state `update.sh` produces.
- With no existing install, the fresh-install path behaves exactly as before.
- The hand-over message names the install directory and the manual way to change
  port/origins.
- `./.t-workflow/scripts/consistency-check.sh` passes.

## Explicitly not
- A `--reconfigure` path to change port/origins/account through the installer.
- Any change to `update.sh`'s own behaviour.
- `README.md` wording — excluded by the plan: its Installing section promises nothing
  about a re-run, and keeping it out keeps this task off every protected surface.

## Decisions made along the way
- "Existing" means a **completed** install — `application-locklane.properties` **and**
  `uninstall.sh` both present — not the properties file alone, which the issue's Goal
  named. `install.sh` writes the properties file before the seed and the service step,
  so a first install that died at the seed would otherwise be handed to `update.sh`
  forever and never get its account; `uninstall.sh` is written last, once the server is
  up (Claude, from the plan, 2026-09-04).
- The hand-over sits after `refuse_inside_server`, so a re-run from a console tab is
  refused with a message naming `install.sh`, the script the person actually ran.
- The hand-over fetches `update.sh` with its own copy of the fresh path's six fetch
  lines rather than moving those into a shared function: the plan's review check wants
  the fresh path additions-only, and the block's comment names the other copy.
- `exec … < /dev/null`: `update.sh` reads stdin nowhere at runtime, but under
  `curl | bash` the pipe still carries the rest of this script, and nothing downstream
  may ever be able to read it (#354).

## Deviations / notes
- none
