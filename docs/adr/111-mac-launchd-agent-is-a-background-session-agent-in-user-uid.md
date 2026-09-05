# ADR-111: The Mac launchd agent is a Background-session agent in user/<uid>

**Status:** Accepted · 2026-09-05
**Deciders:** project owner *(solo phase; the decision follows the experiment
`/t-drive 705` was directed to run, #704)*

This ADR **supersedes [ADR-110](110-mac-launchd-agent-lives-in-gui-uid-and-launchd-changes-ship-only-past-a-real-mac.md)
Decision 1** (the `gui/<uid>` domain) and **resolves its Decision 4** (headless-Mac
support deferred to #704). ADR-110 Decisions 2 (a refused bootstrap keeps the plist)
and 3 (a launchd change ships only past the real-macOS CI job) stay in force
unchanged; ADR-110's own text is not edited (append-only, `CONSTITUTION.md` §2.1).

## Context

Issue #704, under initiative #705. #691 (v0.2.11) had moved the agent to `user/<uid>`
so a Mac could be installed over SSH with nobody at the console; on a real Mac
launchd refused the plist there — 134, "Service cannot load in requested session" —
because a LaunchAgent plist with no `LimitLoadToSessionType` is an Aqua-session agent
and `user/<uid>` is the Background session. #703 (ADR-110) put the agent back in
`gui/<uid>` and added `.github/workflows/mac-lifecycle.yml`, which runs the control
program against the real `launchctl` on a GitHub macOS runner.

#704 asked that job one question before any code changed: does a plist that declares
`LimitLoadToSessionType` = `Background` load into `user/<uid>`? The experiment
(report-only step, run 33944160500 on PR #707, `macos-latest` = macOS 26.5.2 arm64,
2026-09-05):

```
launchctl bootstrap user/501 ~/Library/LaunchAgents/com.locklane.probe.plist
bootstrap exit=0
user/501/com.locklane.probe = { ... state = running ... }
launchd: [user/501:] Bootstrap by launchctl[3763] for <private> succeeded (0: )
```

The same probe was not present in `gui/501` ("Could not find service"), so it loaded
where it was asked to, and only there.

## Decision

1. **`write_launchd_plist` adds `LimitLoadToSessionType` = `Background`, and both
   `launchctl bootstrap` calls (`register`, `start`) target `user/$(id -u)`.** The
   agent no longer needs a graphical login to load: `locklane install`, `register`,
   `start`, `stop`, `restart`, `update` and `uninstall` work from an SSH session on a
   Mac with nobody at the console, which is what #691 set out to do.
2. **Older installs move over on their own.** The lookup checks `user/<uid>` first and
   `gui/<uid>` next; the stop sweep boots out and, if needed, SIGKILLs both. An install
   from v0.2.10 or earlier, or one hand-patched to `gui/<uid>` after v0.2.11 broke,
   is found in `gui/<uid>`, retired, and re-registered in `user/<uid>` by its next
   `stop`/`restart`/`register`/`update`. `status` names the older domain while it lasts.
3. **The macOS CI job proves the pair, not just the load.** The probe step stays as an
   assertion (a refusal fails the job and says this ADR's premise changed), and the
   lifecycle sequence runs in `user/<uid>` after planting a plain older-style agent in
   `gui/<uid>`, asserting it is retired and the new plist carries the session type.
   The stub harness asserts the same in Linux CI, and also that a refused bootstrap
   keeps the plist (ADR-110 Decision 2).
4. **`locklane stop`'s message stays "macOS loads it again at your next login"** —
   `~/Library/LaunchAgents` is scanned when the user's sessions come up, Background
   included — and `README.md` says the same instead of the earlier, wrong "stays down
   across logins".

**Recovery for an install broken by v0.2.11–v0.2.13** is unchanged from ADR-110: run
the next release's `~/.locklane/locklane update`; it fetches this control program
first, which registers into `user/<uid>` with the new plist.

## Rationale

- **The experiment answered the exact question that broke v0.2.11.** What launchd
  refused was an Aqua-session agent in the Background session; a Background-session
  agent is what `user/<uid>` is for. The refusal was never about the domain being
  unavailable to a plain user.
- **Deciding on a CI run, not a laptop, is the point of #704.** Nobody had to probe
  anything by hand; the run's log is the evidence, linked from the task record, and
  the assertion that replaced the experiment keeps that evidence current on every
  run that touches the lifecycle.
- **Both domains stay swept**, so the migration costs an existing install nothing but
  its next restart, and a future change of domain (in either direction) inherits the
  same mechanism.

## Alternatives considered

- **Stay on `gui/<uid>` and document the graphical-login requirement** — the negative
  outcome #704 had prepared for; not taken because the experiment passed, and
  headless installs were #691's explicit goal.
- **A `system`-domain LaunchDaemon** — needs root and is not per-user; rejected by
  #691's own constraints and again here.
- **Keep the report-only experiment step in the workflow** — a step that prints and
  asserts nothing would be noise on every run; as an assertion it is a revisit
  trigger that fires by itself.

## Consequences / revisit triggers

Accepted knowingly, and to be checked on the owner's Mac after the next release (a
note, not a gate): the server and everything a console tab spawns now run in the
user's Background session rather than the Aqua one. Background-session processes have
no access to the window server or Aqua-only services, so a tool run from a console
tab that tries to hand off to the GUI (an `open <url>`, a keychain prompt, a
notification) may fail where it used to work. Locklane itself does not do that, and
the coding agents it runs print URLs rather than opening them, but this is the one
behavioural difference the CI job cannot see.

Also unverified, by design: whether the agent comes up on a Mac after a cold boot with
no login of any kind (the `user/<uid>` domain appears with the user's first session).
GitHub's runners are always logged in; whoever has an SSH-only Mac can look.

Reopen as a new ADR when: the macOS job's probe assertion fails on a newer macOS; a
console-tab tool is found to need the Aqua session (the Background trade-off above
bites); or a headless Mac shows the agent does not come up after a cold boot and that
matters to someone.
