# 643 — Cache client Node/npm in release.yml and sonar.yml
Issue: #643

## Asked
`release.yml`'s `./mvnw -B package -Drevision="$VERSION"` and `sonar.yml`'s
`./mvnw -B verify sonar:sonar` both build the root reactor, which pulls in the `client`
module and triggers `frontend-maven-plugin` to download Node/npm and install packages
into `client/node` and `client/node_modules` on every run — the same cost `ci.yml`
eliminated for its own build step in #641 with an `actions/cache` step. Neither workflow
has that caching today: `release.yml` pays it on every dispatch (there is no
build-input skip like CI's), and `sonar.yml` pays it on every manual quality scan.

## Done when
- `release.yml` has an `actions/cache` step for `client/node` and `client/node_modules`,
  placed after `actions/setup-java` and before the Maven build step, keyed the same way
  `ci.yml` already keys its own
  (`${{ runner.os }}-client-node-${{ hashFiles('client/package-lock.json', 'client/pom.xml') }}`).
- `sonar.yml` has the same step, same placement, same key.
- Both workflows still build and publish/scan correctly — a warm-cache dispatch/run
  downloads no Node/npm into `client/node` (verified by a run's log, or by inspection
  that the step is wired identically to `ci.yml`'s).

## Explicitly not
- `ci.yml`'s own caching (#641, already shipped) — untouched.
- A build-input skip for `release.yml` or `sonar.yml` — both intentionally build
  unconditionally.

## Decisions made along the way
- Both files' cache step is **unconditional** — no `if:` gate — unlike `ci.yml`'s own
  step, which is gated on `steps.build-inputs.outputs.run == 'true'`. Copying that
  condition would be wrong here: neither workflow has a build-input skip (Non-goals),
  so the step would either always run anyway (a no-op `if:`) or, worse, silently gate on
  an output that doesn't exist. (claude, 2026-09-03)
- `release.yml` and `sonar.yml` are flagged as template-owned by
  `.t-workflow/scripts/template-owned-paths.sh --list` (pattern-based: any tracked file
  under a protected `.github/` pattern), but neither actually appears in
  `.template-manifest.json`'s `files` list, and `git log --follow` on both shows they
  were added by ordinary consumer tasks (#134/#146/#157/#210/#476 for `release.yml`,
  #399/#404 for `sonar.yml`), never synced in from `t-workflow`. The manifest is the
  authoritative record of what's template-owned (`docs/architecture/manifest.md`); the
  pattern script over-flags by directory alone. This is the same false-positive class
  already tracked against `t-workflow#122` (filed for `.claude/skills/` files). Treated
  both files as ordinary consumer-owned protected surfaces — plan + review required
  (`CONSTITUTION.md` §3, `.github/`), but no `<!-- local -->` slot needed, since neither
  file is actually template content to preserve across a sync. (claude, 2026-09-03)
- Used the exact same pinned `actions/cache` SHA `ci.yml` already uses
  (`actions/cache@0057852bfaa89a56745cba8c7296529d2fc39830 # v4`) for consistency across
  the three workflows. (claude, 2026-09-03)

## Deviations / notes
- none
