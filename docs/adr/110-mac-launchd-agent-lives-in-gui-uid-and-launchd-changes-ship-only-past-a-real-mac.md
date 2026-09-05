# ADR-110: The Mac launchd agent lives in gui/<uid>, a refused load keeps the plist, and a launchd change ships only past a real Mac

**Status:** Accepted · 2026-09-04
**Deciders:** project owner *(solo phase)*

## Context

Issue #703, under initiative #705. Locklane on macOS runs as a per-user launchd agent
that `scripts/locklane` (the installed control program, #678)
writes to `~/Library/LaunchAgents/com.locklane.server.plist` and loads with
`launchctl bootstrap`. Until v0.2.10 that load targeted `gui/<uid>`, the per-user
domain of a graphical (Aqua) login. #691, released in v0.2.11, moved it to
`user/<uid>` so an install could be made over SSH with nobody logged in at the
console — on the strength of documentation and the lifecycle harness
(`scripts/tests/lifecycle/`), whose `launchctl` is a bash stub. Its own task record
said the real-hardware check was outstanding and quoted a third-party report of
`user/<uid>` refusing with "Input/output error". Three releases shipped anyway.

On 2026-09-04 the owner's Mac (macOS 26.6.2) ran its first `locklane update` to a
release carrying #691. Verified there, by a separate session on that machine:

- `launchctl bootstrap user/$(id -u) <plist>` refuses **every** ordinary LaunchAgent
  plist — Locklane's, and a trivial `/bin/sleep` probe, from any path. The CLI prints
  only `Bootstrap failed: 5: Input/output error`; the reason in the system log
  (`log show --predicate 'process == "launchd"'`) is **134: Service cannot load in
  requested session**. A plist with no `LimitLoadToSessionType` is an Aqua-session
  agent and `user/<uid>` is the Background session.
- `gui/$(id -u)` loads the identical plist.
- After the refused load, `register_cmd` deleted the plist it had just written, so
  `restart`, `start` and every later `register` failed too: the install could not be
  brought back without editing the program by hand.

## Decision

1. **The launchd domain is `gui/<uid>`.** Both `launchctl bootstrap` calls in
   `scripts/locklane` (`register`, `start`) target `gui/$(id -u)`. The lookup and the
   stop sweep keep checking `user/<uid>` as well, so an install that v0.2.11–v0.2.13
   did manage to load there is found, booted out and re-registered in `gui/<uid>` by
   the next `stop`/`restart`/`register`/`update`. `status` names such a leftover
   domain; `stop` says again, truthfully for `gui/<uid>`, that macOS reloads the agent
   at the next graphical login.
2. **A refused bootstrap never removes the plist.** The program prints launchctl's own
   words, leaves `~/Library/LaunchAgents/com.locklane.server.plist` in place, and points
   at `log show --last 5m --predicate 'process == "launchd"'` for launchd's actual
   reason, which launchctl does not print. One refused load is one failed command, not
   a broken install.
3. **Any change to the launchd registration — domain, plist keys, the
   bootstrap/bootout sequence — ships only after the real-macOS CI job passes.**
   `.github/workflows/mac-lifecycle.yml` runs `scripts/locklane register`, `status`,
   `restart`, `stop`, `start` and `uninstall` against the runner's real `launchctl` on
   `macos-latest`, asserting the agent's loaded/unloaded state and a live pid with
   `launchctl print` after each step, on every PR whose diff touches
   `scripts/locklane`, `scripts/tests/lifecycle/**` or the workflow itself, and on
   every push to `main`. The stub harness stays — it is fast and covers escalation,
   refusals and migration paths a runner cannot — but it is never again the only
   evidence for a launchd change.
4. **Headless-Mac support is deferred to #704**, which must first show, in that same
   CI job, whether a plist with `LimitLoadToSessionType = Background` loads into
   `user/<uid>`, and adopt or document accordingly. Nothing about `user/<uid>` is
   promised until that run exists.

**Recovery for an install broken by v0.2.11–v0.2.13:** run the next release's
`~/.locklane/locklane update`. `update` downloads the release's control program before
anything else and hands over to it; that program registers into `gui/<uid>` and the
server comes back. An install that was hand-patched to `gui/<uid>` meanwhile is simply
replaced by the same download.

## Rationale

- **`gui/<uid>` is the domain that works for every existing install.** Every Locklane
  Mac install is made by a person at a graphical login; the one headless case #691
  addressed is rarer than the entire installed base it broke.
- **Deleting the plist on failure converted a recoverable error into an unrecoverable
  state.** The plist is the registration; without it `start` and `restart` have
  nothing to load, and `register` regenerates and deletes it again on the same
  failure. Keeping it costs nothing and leaves `launchctl bootstrap` by hand as a way
  back.
- **Stubs cannot say no.** The stub `launchctl` accepts whatever domain the program
  names; only launchd can refuse. A guard that runs the real thing is the only kind
  that would have caught #691, and GitHub's macOS runners have a graphically logged-in
  user, so `gui/<uid>` is exercised the way an install is.
- **The CI context is not in branch protection's required list, and that is
  accepted for now.** The required-contexts list is asserted by the template-owned
  `.t-workflow/scripts/github-bootstrap.sh` (`checks` and `cold-review`), which would
  wipe a locally-added context the next time it runs. `/t-ship` watches every check
  on the PR and stops on a red one (ADR-008), so the pipeline's own merge path still
  blocks. Making the list extensible is an upstream t-workflow change (proposed in
  #703's record).
- **Job-level path gating, not workflow-level `paths:`.** A workflow skipped by
  `paths:` reports nothing, which would block a PR forever if the context were ever
  made required; a job skipped by its own `if:` reports "skipped", which passes.

## Alternatives considered

- **Keep `user/<uid>` and add `LimitLoadToSessionType = Background`** — the likely
  shape of headless support, but untested on hardware at the time of this decision;
  shipping it now would repeat #691's mistake. Deferred to #704 with the CI experiment
  as its first step.
- **A `system`-domain LaunchDaemon** — loads with nobody logged in, but needs root to
  write `/Library/LaunchDaemons` and to `bootstrap system`, which #691's own Done-when
  ruled out; also not per-user.
- **Replace the hand-rolled lifecycle code with Homebrew (`brew services`) or a
  service library (kardianos/service, the `service-manager` crate)** — a distribution
  decision, not a fix; it would not have helped the installs already broken, and it
  deserves its own conversation. Not part of this ADR.
- **Make `mac-lifecycle` a required status check now via the API** — would be undone by
  the next `github-bootstrap.sh` run; instead the upstream change is proposed and
  `/t-ship`'s CI watch is relied on meanwhile.

## Consequences / revisit triggers

- `scripts/tests/lifecycle/run.sh` asserts `gui/<uid>` as the registration target,
  the retirement of a `user/<uid>` leftover, and that a refused bootstrap keeps the
  plist; `.github/workflows/mac-lifecycle.yml` is the hardware guard.
- Revisit as a new ADR when: #704 proves a `user/<uid>` shape on the runner (the domain
  decision reopens); the required-checks list becomes extensible upstream (the context
  should then be required); or GitHub's macOS runners stop providing a graphical
  session (the guard's precondition, asserted by the job's first step, would fail).
