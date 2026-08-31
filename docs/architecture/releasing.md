# Releasing

**Status:** binding convention.

Releases are cut by manually dispatching `.github/workflows/release.yml` (the `Release`
workflow) — `workflow_dispatch` only, since #207. Nothing releases automatically on a
push. One dispatch publishes two things from the same build: the rolling `latest`
pre-release (#95), a moving pointer at the newest build, and the permanent per-version
release (#98, #463) that appears once and never changes.

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

Dispatch the `Release` workflow (Actions → Release → Run workflow, or
`gh workflow run release.yml`). The `publish` job:

1. Derives the release version by stripping `-SNAPSHOT` from the current `<revision>`
   (`0.1.0-SNAPSHOT` → `0.1.0`). No literal version string lives in the workflow file.
2. Builds and tests the jar with the version overridden to that bare release version,
   so the published jar identifies itself as the release, never as a SNAPSHOT.
3. Upserts the rolling `latest` pre-release with that build.
4. Creates the permanent release `v<version>` — not flagged as a pre-release, carrying
   the runnable jar as `locklane.jar`.

After the cut, when the next development cycle should build toward a different version,
the maintainer bumps `<revision>` to the new `X.Y.Z-SNAPSHOT` — again an ordinary PR.

## Immutability

A released version is immutable. If a dispatch derives a version whose `v<version>`
release already exists — the version wasn't bumped since the last cut, or a stale run
re-triggered — the permanent-release step fails the run loudly, touching neither the
existing release nor its tag. To publish again, bump `<revision>` to a new version
first.

## Non-goals

- Nothing here bumps `<revision>` automatically (changelog generation, semantic-release,
  or similar) — a human decides when a version is cut.
- The permanent release and the rolling `latest` release coexist; cutting a version never
  removes `latest`.
