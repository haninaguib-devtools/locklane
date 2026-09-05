# 704 — Headless-Mac install: prove LimitLoadToSessionType=Background in user/<uid> on real hardware before changing scripts/locklane
Issue: #704 · Part of: #705

## Asked
Let Locklane run on a Mac with no graphical login (a headless Mac reached over SSH),
which #691 tried by registering into `user/<uid>` and which broke every ordinary Mac
install (reverted by #703, ADR-110): a LaunchAgent plist with no
`LimitLoadToSessionType` is an Aqua-session agent, and launchd refuses it in the
Background session with 134. The candidate fix is a plist with
`LimitLoadToSessionType` = `Background` loaded into `user/<uid>`. Whether launchd
accepts that is decided by an experiment on GitHub's macOS runner, in this task's own
PR — never by hand, never by a human on a laptop. The task completes either way: adopt
the approach if the experiment passes, or ship the finding and the documented
requirement if it does not.

## Done when
- The real-macOS CI job gains a step that bootstraps a probe plist (Label
  `com.locklane.probe`, `/bin/sleep 300`, RunAtLoad, `LimitLoadToSessionType` =
  `Background`) into `user/$(id -u)`, prints the exit code, `launchctl print` output
  and the launchd log line, then boots it out. The step's log is the recorded result
  and the run is linked from this record.
- If the probe loads: `scripts/locklane` adopts the Background plist and `user/<uid>`,
  keeping the two-domain sweep; stub harness and the macOS job assert the new target;
  ADR and changelog record it.
- If the probe does not load: `scripts/locklane` is unchanged; the record and an ADR
  carry the exit code and launchd reason; `README.md`'s install section states that a
  Mac install needs a graphically logged-in user.
- `bash scripts/tests/lifecycle/run.sh`, `./.t-workflow/scripts/consistency-check.sh`
  and the macOS CI job are green on the PR.

## Explicitly not
- Behaviour on a Mac where nobody has ever logged in graphically (a cold headless
  boot): not checkable on a runner; a note for whoever has such a Mac.
- A `system`-domain daemon needing root: a possible future direction to mention, not
  to build.
- `CHANGELOG.md`: generated at release cut from squash subjects (as in #703).

## Decisions made along the way
- The experiment step is committed and run first, alone, and only then does the task
  branch on the outcome; afterwards the step stays in the workflow as an assertion of
  whichever answer the run gave, so a change in macOS's behaviour fails the job
  instead of going unnoticed (Claude, 2026-09-04; from the plan).
- `README.md`'s pre-existing sentence that a stopped agent "stays down across logins"
  (written by #678, wrong for `gui/<uid>` and contradicting the program's own message
  since #703) is corrected here, inside the one README section this task owns
  (Claude, 2026-09-04).

## Deviations / notes
- **Blocker #703 is a merged-but-open sibling.** Its PR #706 is squash-merged into
  `wip/705-integration` (commit `235453c`) with a `readiness: ready` cold review; the
  issue stays open until the initiative's aggregate PR merges to `main`
  (`Closes #703` there). Per `/t-drive` Phase 2 step 1 a sibling whose outcome is
  "merged" no longer holds a child, so the driving session treated the blocker as
  discharged and this branch is cut from the integration tip that contains it — the
  same reading the #462 and #535 driven runs applied. `/t-work`'s literal gate
  (`check-blocker-gate.sh`) would read the open issue as unsatisfied; reconciling that
  gate with ADR-004 remains an upstream t-workflow proposal, not opened.
- Experiment result: (filled in once the run completes — see below).
