# Releasing

**Status:** binding convention.

Two release mechanisms run from `.github/workflows/release.yml` on every push to `main`:
a rolling `latest` release (#95) that always points at the newest build, and a permanent
per-version release (#98) that appears once and never changes.

## Cutting a version

There is exactly one file to edit: the `<revision>` property in the root `pom.xml`.
Every module's `<parent><version>` reads `${revision}` (#97), so this is the single
source of the project version.

- While `<revision>` ends in `-SNAPSHOT` (e.g. `0.1.0-SNAPSHOT`), pushes to `main` update
  only the rolling `latest` release. No permanent release is created.
- Removing the `-SNAPSHOT` suffix (e.g. `0.1.0`) and pushing that commit to `main` cuts a
  permanent release tagged `v0.1.0`: not flagged as a pre-release, carrying the runnable
  jar as `locklane.jar`.

A human decides when to make that edit — nothing bumps the version automatically.

## What happens on merge

The `publish` job in `release.yml` reads the version from `engine/pom.xml` at build
time (no literal version string lives in the workflow), builds and tests the jar, then:

1. Always upserts the rolling `latest` release with that build.
2. If the version has dropped `-SNAPSHOT`, also creates the permanent release `v<version>`.

A released version is immutable: if a workflow run ever finds `v<version>` already
exists — the version wasn't bumped before a second push, or a stale run re-triggers —
the permanent-release step fails the run loudly instead of overwriting or skipping it.
To publish again, bump `<revision>` to a new version first.

## Non-goals

- Nothing here bumps `<revision>` automatically (changelog generation, semantic-release,
  or similar) — a human decides when a version is cut.
- The permanent release and the rolling `latest` release coexist; cutting a version never
  removes `latest`.
