# 703 — Put the Mac launchd agent back on gui/<uid>, keep the plist on a failed bootstrap, prove it on a real macOS runner
Issue: #703 · Part of: #705

## Asked
Every Mac install broke on its first `locklane update` to v0.2.11 or later: the update
could not load the server into launchd and then deleted the plist it had just written,
leaving an install that could not start, restart or register. Verified on the owner's
Mac (macOS 26.6.2, 2026-09-04): `launchctl bootstrap user/$(id -u)` refuses every
ordinary LaunchAgent plist (launchd 134, "Service cannot load in requested session",
shown by the CLI only as "5: Input/output error"); `gui/$(id -u)` accepts the identical
plist. #691 (v0.2.11) made the switch on the Linux stub harness alone. Put the
registration back on `gui/<uid>`, make a refused load leave the install recoverable,
add a CI job that runs the control program against the real `launchctl` on a GitHub
macOS runner, and record the decision in an ADR.

## Done when
- Both `launchctl bootstrap` calls in `scripts/locklane` target `gui/$(id -u)`; the
  two-domain sweep and lookup keep finding and retiring a `user/<uid>` leftover.
- A failed bootstrap in `register` no longer removes the plist; it prints launchctl's
  stderr and points at `log show`; `start` reports the same way.
- `status` no longer prints the "still in the old gui/<uid>" note; `stop` says the
  agent comes back at the next login as well as via `locklane start`.
- `bash scripts/tests/lifecycle/run.sh` passes with `gui/<uid>` asserted as the target,
  `user/<uid>` only as a legacy domain that is swept, and a scenario planting a
  `user/<uid>` agent and confirming it is retired.
- A macOS CI job runs `register`, `status`, `restart`, `stop`, `start`, `uninstall`
  against the real `launchctl` with a small real jar, asserting state after each step,
  on every PR touching `scripts/locklane`, `scripts/tests/lifecycle/**` or the
  workflow, and on every push to `main`; a PR putting `user/$(id -u)` back fails it.
- A new ADR records the domain, why `user/<uid>` was reverted, the deferral of
  headless support to #704, and that launchd changes need the real-macOS job.
- The changelog criterion — see Deviations: met by this task's squash subject at the
  next release cut, with the recovery guidance in the ADR.
- `./.t-workflow/scripts/consistency-check.sh` passes.

## Explicitly not
- Headless-Mac support (SSH-only install, no Aqua session) — split to #704.
- Replacing the lifecycle code with Homebrew or a service library — a separate
  design conversation, no issue yet.
- Linux/systemd behaviour — unchanged.

## Decisions made along the way
- The macOS job is its own workflow, `.github/workflows/mac-lifecycle.yml`, not a job
  in `ci.yml` (Claude, 2026-09-04): `ci.yml` is template-owned and its local slots
  hold only extra steps of the ubuntu `checks` job, which cannot run on a macOS
  runner. Its context `mac-lifecycle` is not added to branch protection's required
  list: that list is asserted by the template-owned `github-bootstrap.sh` and would be
  wiped at its next run; `/t-ship`'s attended CI watch (ADR-008) stops on any red
  check, so the merge path still blocks. Path gating is a job-level `if:` fed by a
  small `decide` job, never a workflow-level `paths:` filter, so the context can be
  made required later without blocking untouched PRs.
- The workflow runs on draft PRs too (Claude, 2026-09-04): macOS minutes are free on a
  public repository and a driven child PR is readied only moments before its merge, so
  skipping drafts (as `ci.yml` does to save minutes) would delay the hardware signal to
  the aggregate PR.
- The migration scenario keeps its planted agent in `gui/<uid>` (where a pre-v0.2.11
  install lived) and a new scenario, `retires-user-domain-agent`, covers the
  v0.2.11–v0.2.13 leftover; a second new scenario, `bootstrap-failure-keeps-plist`,
  is the regression test for the deleted plist, driven by a `bootstrap-fails` knob
  added to the stub `launchctl` (Claude, 2026-09-04).
- ADR-110's one-line rule went into `CONSTITUTION.md` §4 (the local slot) per §2.3; the
  plan was amended before work started to allow that path (Claude, 2026-09-04).

## Deviations / notes
- **`CHANGELOG.md` is not edited.** The issue's Done-when asks for an unreleased
  changelog entry, but this repo's changelog is generated at release cut from squash
  subjects (`scripts/generate-release-notes.sh`, `docs/architecture/releasing.md`
  § Release notes); no task edits it. The fix appears under Fixes at the next cut from
  this task's subject, and the recovery guidance ("run the new release's
  `locklane update`") is in ADR-110. Noted in the plan; approved implicitly by the
  human's `/t-drive 705` on the planned scope.
- The plan's grep for the bootstrap call sites needs `grep -F` (`$(` inside a basic
  regular expression does not match literally); the check was run that way.
- **Proposal for the human (upstream, `haninaguib-devtools/t-workflow`):** make
  `github-bootstrap.sh`'s required-status-checks list extensible (a local slot or a
  consumer-supplied list) so a consumer-local CI context such as `mac-lifecycle` can
  be required in branch protection without being wiped at the next bootstrap run.
- Real-hardware result behind this task: recorded in the issue and ADR-110 from a
  separate session on the owner's Mac; this session had no macOS environment, which is
  exactly why the CI job exists. The macOS job's first green run on this PR is the
  hardware verification for the reverted domain.
