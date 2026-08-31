# Releasing

**Status:** binding convention.

Releases are cut by manually dispatching `.github/workflows/release.yml` (the `Release`
workflow) — `workflow_dispatch` only, since #207. Nothing releases automatically on a
push. One dispatch publishes one thing: the permanent per-version release (#98, #463)
that appears once and never changes. That release is the only distribution channel —
`install.sh` and `update.sh` download the jar of the newest permanent (non-prerelease)
release, the same release the in-app update banner announces, so what an update installs
is always what the banner named. The rolling `latest` pre-release that used to accompany
each cut (#95), a moving pointer at the newest build, was retired by #465. Every release
carries curated notes: its body is that version's section of the committed root
`CHANGELOG.md`, merged to `main` before the dispatch and extracted verbatim at cut time
(#464, § Release notes below).

## The `-SNAPSHOT` convention

`<revision>` in the root `pom.xml` — the single source of the project version, read as
`${revision}` by every module (#97) — always ends in `-SNAPSHOT` on `main`
(e.g. `0.1.0-SNAPSHOT`). That suffix is what makes a non-release build identifiable by
its version string: anything reporting `X.Y.Z-SNAPSHOT` was built from `main`, not from
a release cut.

The maintainer chooses the *next* version — major, minor, or patch — by editing that one
`<revision>` line through an ordinary PR, whenever they decide, ahead of the cut. The
suffix stays on; nothing bumps the version automatically.

## Cutting a version

Before the dispatch, the version's notes must be on `main`: run
`scripts/generate-release-notes.sh generate --version X.Y.Z` (X.Y.Z being `<revision>`
without the suffix) and land the `CHANGELOG.md` change as an ordinary pipeline PR — the
**notes PR**, where a human reviews the generated notes and may add an editorial
summary like any other reviewed change. The workflow never writes to `main` and never
opens a PR; `main` only moves by a human-confirmed PR (CONSTITUTION §1).

Then dispatch the `Release` workflow (Actions → Release → Run workflow, or
`gh workflow run release.yml`). The `publish` job:

1. Derives the release version by stripping `-SNAPSHOT` from the current `<revision>`
   (`0.1.0-SNAPSHOT` → `0.1.0`). No literal version string lives in the workflow file.
2. Extracts that version's section from `CHANGELOG.md`
   (`scripts/generate-release-notes.sh extract`). No section — the notes PR has not
   landed — fails the run loudly before anything is built or published.
3. Builds and tests the jar with the version overridden to that bare release version,
   so the published jar identifies itself as the release, never as a SNAPSHOT.
4. Creates the permanent release `v<version>` — not flagged as a pre-release, carrying
   the runnable jar as `locklane.jar`, with the extracted section as its body.

After the cut, when the next development cycle should build toward a different version,
the maintainer bumps `<revision>` to the new `X.Y.Z-SNAPSHOT` — again an ordinary PR.

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

A released version is immutable. If a dispatch derives a version whose `v<version>`
release already exists — the version wasn't bumped since the last cut, or a stale run
re-triggered — the permanent-release step fails the run loudly, touching neither the
existing release nor its tag. To publish again, bump `<revision>` to a new version
first.

## Non-goals

- Nothing here bumps `<revision>` automatically (no semantic-release or
  commit-message-driven versioning) — a human decides when a version is cut. The
  generated notes inform that human; they never trigger anything.
- No backfilled notes: `CHANGELOG.md` starts at the first release cut after #464;
  releases published before it keep their original bodies.
- No rolling pre-release channel: nothing republishes the retired `latest` pre-release,
  and no moving tag points at the newest build. The newest permanent release is the one
  download channel.
