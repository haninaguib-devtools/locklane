# 95 — Publish a rolling latest release of the runnable jar on every main commit
Issue: #95

## Asked
Today there is no way to get a runnable copy of Locklane without cloning the repository
and building it. This task makes every commit that lands on `main` produce a downloadable
runnable jar, attached to a single GitHub release that always points at the newest build.
Someone who wants to run Locklane downloads one file from a link that never changes and
runs `java -jar locklane.jar`.

The release is deliberately a rolling pointer at the newest build, not a permanent
version, since the project version (`0.1.0-SNAPSHOT`) does not change from commit to
commit.

## Done when
- A push to `main` triggers a workflow that runs `./mvnw -B package` (full test suite
  included) and publishes the repackaged engine jar. A build whose tests fail publishes
  nothing.
- Exactly one release serves this purpose: tag `latest`, flagged as a pre-release.
- The release title carries the version read from `engine/pom.xml` at build time, plus
  the run number and short commit sha. No literal version string appears in the workflow
  file.
- The jar is attached under the fixed asset name `locklane.jar`, so
  `.../releases/download/latest/locklane.jar` always resolves to the newest build.
- Publishing updates the existing release and replaces its asset in place rather than
  deleting and recreating it.
- Two pushes to `main` landing close together cannot both publish: a concurrency group
  makes the older run stand down.
- `contents: write` is granted on the publishing job only, not at the workflow's top
  level.
- Human judgment, not asserted by CI: downloading the published `locklane.jar` and
  running `java -jar locklane.jar` starts the app.

## Explicitly not
- Permanent per-version releases (`v0.1.0` and successors) and any pom-version-bumping
  automation — split to #98.
- Collapsing the duplicated version string between `pom.xml` and `engine/pom.xml`.
- Smoke-testing that the published jar actually boots.
- Docker images, and publishing to Maven Central.
- Anything inside the running app (version display, update banner, self-update).

## Decisions made along the way
- none

## Deviations / notes
- none
