# 672 — Keep the service's baked-in PATH single-line when a login-shell startup file prints
Issue: #672

## Asked
The systemd unit / launchd agent that runs the server does not get a terminal's PATH,
so `install.sh` and `update.sh` resolve one from a login shell (`$SHELL -lc 'echo
$PATH'`, #422) and bake it into the unit's `Environment=PATH=` line or the plist's
`EnvironmentVariables.PATH`. Both scripts take that command's entire stdout as the
value. Any startup file that prints — a greeting in `~/.zprofile`, a version manager's
banner, a `brew shellenv` notice — makes the value multi-line; the newline is not a
PATH separator, so the first real entry (typically `/opt/homebrew/bin`) is glued to the
junk before it and the running server cannot find `gh` — while `command -v gh` in every
terminal, and in `$SHELL -lc`, still succeeds. The person sees `Could not run gh — is it
installed and on PATH?` with gh plainly installed.

`resolve_login_path` (duplicated byte for byte in `install.sh` and `update.sh`) must
keep only the PATH itself out of the shell's output, and fall back to the installer's
own `$PATH` when nothing usable came back. The two copies stay identical.

## Done when
- With `SHELL` pointed at a stub shell that prints `hello` before running the command
  with `PATH=/opt/x/bin:/usr/bin`, `resolve_login_path` prints exactly
  `/opt/x/bin:/usr/bin` (verified by extracting the function from `install.sh` and
  running it; the same for `update.sh`).
- With a stub whose output is empty, it prints the caller's `$PATH`.
- `diff <(sed -n '/^resolve_login_path() {/,/^} # end resolve_login_path/p' install.sh)
  <(sed -n '/^resolve_login_path() {/,/^} # end resolve_login_path/p' update.sh)` is
  empty.
- `./.t-workflow/scripts/consistency-check.sh` passes.

## Explicitly not
- Re-running `install.sh` over an existing install behaving as an update — proposed,
  not opened.
- `stop.sh`'s 30s wait / console-tab refusal — proposed, not opened.

## Decisions made along the way
- The PATH is read from one **tagged** line (`__LOCKLANE_PATH__=<value>`, the last one
  if several) rather than from "the last non-empty line" the issue sketched: a login
  shell also runs `~/.zlogout` / `~/.bash_logout` on exit, *after* the command, so
  output there (Debian's default `.bash_logout` runs `clear`) would have been the last
  line and corrupted the value just the same. The tag makes the read independent of
  anything printed before or after. The issue's stub-shell criterion is met in spirit
  and in the verification below: chatter before and after the command, the bare PATH
  out (Claude, 2026-09-04).
- No tagged line at all (the stub printed nothing, the shell failed, or the result has
  no `/` in it) falls back to the caller's own `$PATH` — the same fallback as before,
  widened from "empty" to "unusable".
- The function gains a `} # end resolve_login_path` marker, matching the other
  duplicated functions, so the two copies can be diffed by range.

## Deviations / notes
- none
