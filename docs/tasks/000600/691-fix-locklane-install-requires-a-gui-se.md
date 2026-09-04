# 691 — Fix: locklane install requires a GUI session, fails headless (launchctl 125)
Issue: #691

## Asked
Installing Locklane on a Mac with no active graphical (Aqua) login session — a headless
Mac, or one reached only over SSH/Tailscale — fails during `launchctl bootstrap` with
exit code 125, because `scripts/locklane` registers the server in the `gui/$(id -u)`
launchd domain, which only exists while the target user has an active graphical session.
The fix must keep the per-user property `gui/<uid>` already gives for free — a different
user on the same Mac installs their own Locklane on their own port with no root/sudo and
no collision with another user's install.

## Done when
- `scripts/locklane`'s `install`/`register`, `start`, `stop`, and `uninstall` commands
  work correctly on a Mac with no user logged into the console at all (an SSH-only
  session, no Screen Sharing) — the launchd domain used no longer requires a GUI session
  to exist.
- A second, different user on the same Mac can independently install their own Locklane
  instance on a different port, with no root/sudo requirement, and no collision with the
  first user's install.
- `scripts/tests/lifecycle/` asserts the chosen domain/registration approach, including a
  scenario covering two distinct users, and passes.
- Existing installs on the old `gui/<uid>` LaunchAgent are handled by `scripts/locklane`
  itself.

## Explicitly not
- Linux/systemd support for the control program — unaffected, out of scope.
- The port/origin/bootstrap-account prompts in the installer flow — unchanged.

## Decisions made along the way
- Chose the `user/<uid>` launchd domain over a per-user-namespaced LaunchDaemon in the
  `system` domain (Claude, 2026-09-04). A `system`-domain LaunchDaemon would need root to
  write `/Library/LaunchDaemons` and to `bootstrap system`, which the issue's Done-when
  explicitly rules out ("no root/sudo requirement"); `user/<uid>` stays per-uid (same
  free collision-avoidance property as `gui/<uid>`) and, per available documentation, is
  reachable over an SSH ("Background") session without requiring an Aqua login.
- Migration is handled inside the existing lifecycle, not as a one-off step: every
  `stop`/`restart`/`register`/`update` now sweeps *both* the `user/<uid>` and the old
  `gui/<uid>` domain when boot-out-ing and SIGKILL-escalating the agent, and `main_pid` /
  `service_loaded` / `service_state_line` check `user/<uid>` first, falling back to
  `gui/<uid>` for an install not yet migrated. A bare `locklane start` on an install
  still loaded under the old domain reports "already running" and does not itself
  migrate it — the next `stop`, `restart`, `register`, or `update` does, since all of
  those go through `stop_server`. This is documented behavior, not a gap: `install.sh`'s
  and `update.sh`'s own flows always pass through `register_cmd` at least once.
- Dropped the "comes back at the next login" claim from `locklane stop`'s launchd
  message. That claim was accurate for `gui/<uid>` (macOS auto-scans and reloads
  `~/Library/LaunchAgents/*.plist` into `gui/<uid>` at every fresh Aqua login,
  independent of this program). No equivalent auto-reload convention for `user/<uid>` is
  documented, so the message now says only what `locklane start` does.

## Deviations / notes
- **Real-hardware verification could not be performed in this session** — no macOS
  environment is reachable from here, only Linux stubs. The issue itself calls this out
  explicitly ("whoever picks it up should test on real hardware rather than assume from
  documentation alone"); this task delivers the code and test-suite change but the
  hardware check is still outstanding. What's confirmed vs. still open:
  - Confirmed by available documentation (not hardware): `user/<uid>` is reachable
    without an Aqua session, including over SSH, and does not need `launchctl enable` or
    root to bootstrap into as the owning user.
  - **Open, needs a real headless Mac**: whether a job bootstrapped into `user/<uid>`
    survives a full reboot with *no* login of any kind ever having occurred (the
    `RunAtLoad`/`KeepAlive` unattended-restart guarantee this program relies on for
    "runs unattended"). One third-party account found during research (an unverified
    blog post, not Apple documentation) describes `launchctl bootstrap user/<uid>`
    failing with `Input/output error` on a fresh headless boot until something else
    (that author used a root LaunchDaemon) re-primed the domain — which would mean
    `user/<uid>` alone does not fully close the loop on a cold, sessionless reboot. A
    system-domain bridge would need root, conflicting with this issue's no-sudo
    constraint, so it is deliberately not implemented here. Flagging for the human to
    verify on real hardware before relying on this for unattended boot recovery; if it
    turns out `user/<uid>` does not survive a cold reboot, that is a follow-up issue, not
    a defect in this change (which does solve the literal, tested scenario: the commands
    working correctly from an SSH-only session while the Mac is up).
  - Added a `stubs/id` test stub (`STUB_UID`, defaulting to the real `id -u`) so the
    lifecycle harness can simulate two distinct users' uids without needing two real
    macOS accounts — that gap didn't exist before since the harness only ever ran as one
    OS user.
