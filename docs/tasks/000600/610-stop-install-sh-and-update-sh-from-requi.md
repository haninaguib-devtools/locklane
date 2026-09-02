# 610 — Stop install.sh and update.sh from requiring a gh login
Issue: #610

## Asked
A fresh `curl … install.sh | bash` on a machine where the GitHub CLI is installed but
not logged in should complete without ever asking for `gh auth login`. Today it stops
right after the jar download: the jar comes down fine (`gh release download` works
unauthenticated on a public repo), but the next line fetches `update.sh` through
`gh api`, and every `gh api` call needs a token. #549 (PR #559) removed the installer's
login check and wrote a comment saying gh "no longer needs to be logged in on this
host", but left that fetch in place. `update.sh` has the same leftover in a different
form: it exits with "gh is not logged in" before doing anything, even though its only
gh call is the same unauthenticated release download. Make both scripts honour what
#549 already promised: fetch `update.sh` as a plain raw-file download (the same channel
the README's install one-liner uses for `install.sh` itself), and drop the
`gh auth status` gate and the "then run 'gh auth login'" wording from `update.sh`.

## Done when
- `install.sh` contains no `gh api` call: `grep -c 'gh api' install.sh` prints `0`.
- `install.sh` fetches `update.sh` from
  `https://raw.githubusercontent.com/haninaguib-devtools/locklane/main/update.sh` (or an
  equivalent unauthenticated raw URL) with `curl -fsSL`, and still ends up with an
  executable `$INSTALL_DIR/update.sh`.
- `update.sh` contains no `gh auth` call and no `gh auth login` text:
  `grep -c 'gh auth' update.sh` prints `0`. Its "gh is required" error no longer tells
  the user to log in.
- With a clean `HOME`, an empty `GH_CONFIG_DIR`, and `GH_TOKEN`/`GITHUB_TOKEN` unset,
  running `install.sh` on this host reaches the "Port to run on" prompt without printing
  "gh auth login" (human-run check: it needs a terminal and java).
- The two functions the scripts share (`write_uninstall_script`, `resolve_login_path`)
  remain byte-for-byte identical between `install.sh` and `update.sh` — the diff check
  from #392 still passes.
- The #551/#549 comment block in `install.sh` is updated so it no longer claims
  something the script contradicts.

## Explicitly not
- Removing the `gh` dependency altogether. `gh release download` is still the way both
  scripts resolve the newest permanent release, and the engine still shells out to gh
  for projects with no account chosen. gh must be installed; it need not be logged in.
- Any change to the engine, the accounts page, or the in-app update banner.
- `README.md`: the plan keeps it out of the diff — its Installing section already says
  Locklane acts through the accounts signed in to it, not the host's `gh` login, so
  nothing there needs to follow.

## Decisions made along the way
- The raw URL in `install.sh` is built from the existing `$REPO` variable
  (`https://raw.githubusercontent.com/$REPO/main/update.sh`) rather than spelled out
  literally, so the jar download and the script download keep pointing at the same
  repository (agent, 2026-09-02, per the plan's Risks section).
- `update.sh`'s "gh is required" check stays; only its "then run 'gh auth login'" tail
  and the separate `gh auth status` gate go, since `gh release download` is the one gh
  call left and it needs no login on a public repo (agent, 2026-09-02, per the issue).

## Deviations / notes
- Dead end worth remembering: the done-when check `grep -c 'gh api' install.sh` is a
  literal string count, so the explanatory comments in `install.sh` must not spell out
  the retired command by name either. The first draft did and failed that check; the
  comments now say "gh's REST API subcommand" instead.
