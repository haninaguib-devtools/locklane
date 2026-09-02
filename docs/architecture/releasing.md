# Releasing

**Status:** binding convention.

Releases are cut by dispatching `.github/workflows/release.yml` (the `Release`
workflow) with the release version as its one input — `workflow_dispatch` only, since
#207; the explicit `version` input since #602 (ADR-109 D2). Nothing releases
automatically on a push. One dispatch publishes one thing: the permanent per-version
release (#98, #463) that appears once and never changes. That release is the only distribution channel —
`install.sh` and `update.sh` download the jar of the newest permanent (non-prerelease)
release, the same release the in-app update banner announces, so what an update installs
is always what the banner named. The rolling `latest` pre-release that used to accompany
each cut (#95), a moving pointer at the newest build, was retired by #465. Every release
carries curated notes: its body is that version's section of the committed root
`CHANGELOG.md`, merged to `main` before the dispatch and extracted verbatim at cut time
(#464, § Release notes below). `/l-release <version>` (`.claude/skills/l-release/
SKILL.md`) runs the whole sequence below as one task and one dispatch, with one human
stop (ADR-109 D1).

## The `-SNAPSHOT` convention

`<revision>` in the root `pom.xml` — the single source of the project version, read as
`${revision}` by every module (#97) — always ends in `-SNAPSHOT` on `main`
(e.g. `0.1.0-SNAPSHOT`). That suffix is what makes a non-release build identifiable by
its version string: anything reporting `X.Y.Z-SNAPSHOT` was built from `main`, not from
a release cut.

The maintainer chooses the *next* version — major, minor, or patch — by editing that one
`<revision>` line through an ordinary PR. Since #602 that edit rides in the release
task's own PR (§ Cutting a version): once `v<version>` is cut, `main` already builds
toward the next snapshot. The suffix stays on; nothing bumps the version automatically.

## Cutting a version

The version to cut is `<revision>` without its suffix: `scripts/release.sh gate X.Y.Z`
refuses when `<revision>` is not `X.Y.Z-SNAPSHOT`, or when a `vX.Y.Z` tag or release
already exists (§ Immutability). That gate runs before anything is written.

Before the dispatch, one PR — the **release PR**, an ordinary pipeline task with Scope
`CHANGELOG.md`, `pom.xml` — lands two things on `main` together: the version's notes
(`scripts/generate-release-notes.sh generate --version X.Y.Z`, reviewed by a human who
may add an editorial summary like any other reviewed change) and the `<revision>` bump
to the next `X.Y.Z-SNAPSHOT`. Neither file is a build input for the PR's CI
(`scripts/build-inputs.sh`: a `<revision>`-only `pom.xml` change is excluded, ADR-109
D3), so that PR runs no Maven; the push to `main` after its merge does. The workflow
never writes to `main` and never opens a PR; `main` only moves by a human-confirmed PR
(CONSTITUTION §1).

Then dispatch the `Release` workflow with the version — `scripts/release.sh dispatch
X.Y.Z`, which is what `/l-release` runs after the release PR's merge gate is confirmed
(Actions → Release → Run workflow, or `gh workflow run release.yml -f version=X.Y.Z`,
does the same by hand). The `publish` job:

1. Checks the `version` input is a bare `X.Y.Z`. No version is ever derived from the
   pom: by now `<revision>` on `main` is already the *next* snapshot.
2. Refuses when tag or release `v<version>` already exists (§ Immutability), before a
   build minute is spent.
3. Extracts that version's section from `CHANGELOG.md`
   (`scripts/generate-release-notes.sh extract`). No section — the release PR has not
   landed — fails the run loudly before anything is built or published.
4. Builds and tests the jar with `-Drevision=<version>`, so the published jar
   identifies itself as the release, never as a SNAPSHOT. This is a full
   build-and-test: it produces the published artifact.
5. Creates the permanent release `v<version>` — not flagged as a pre-release, carrying
   the runnable jar as `locklane.jar`, with the extracted section as its body.

`scripts/release.sh dispatch` then watches the run to its conclusion and confirms the
published body equals the `CHANGELOG.md` section on `origin/main`, exiting non-zero
with a one-line reason otherwise. No follow-up bump PR is needed: the release PR
already moved `<revision>` on.

## Release notes

Notes are curated, not hand-typed: `scripts/generate-release-notes.sh generate`
collects the first-parent squash subjects on `main` since the previous release tag
(newest `v*` reachable, overridable with `--prev`), maps each `[<id>] <title> (#<pr>)`
subject to its tracker issue, and groups the entries by the issue's classification
label — `enhancement` → Features, `bug` → Fixes, `documentation` → Documentation,
`question` or anything else → Other. A subject that does not match the convention is
listed under Other verbatim rather than dropped. The section heading is
`## v<version> — <date>`; the date is an explicit input defaulting to today, so the
same inputs produce the same notes. Sections sit newest-first in `CHANGELOG.md`.

Generation runs once, in the session preparing the notes PR — it needs git history and
tracker access. The workflow runs only the same script's `extract` mode, which needs
neither: the release body is the committed section, byte for byte, so what the human
reviewed in the notes PR is exactly what the Releases page shows.

## Immutability

A released version is immutable. If a dispatch names a version whose `v<version>` tag
or release already exists — last release's number typed again, or a stale run
re-triggered — the run fails loudly before building, touching neither the existing
release nor its tag; `scripts/release.sh gate` refuses the same version before a task
is even opened. To publish again, cut a new version.

## Non-goals

- Nothing here bumps `<revision>` automatically (no semantic-release or
  commit-message-driven versioning) — a human decides when a version is cut. The
  generated notes inform that human; they never trigger anything.
- No backfilled notes: `CHANGELOG.md` starts at the first release cut after #464;
  releases published before it keep their original bodies.
- No rolling pre-release channel: nothing republishes the retired `latest` pre-release,
  and no moving tag points at the newest build. The newest permanent release is the one
  download channel.
