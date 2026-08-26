# 98 — Cut permanent per-version releases when the pom version drops -SNAPSHOT
Issue: #98

## Asked
The rolling `latest` release built in #95 always serves the newest build, but it keeps no
history: yesterday's jar is gone the moment a new commit lands, and there is no such
thing as "version 0.1.0" a person can point at, download again, or report a bug against.
This task adds permanent releases, cut from real version numbers, alongside the rolling
one — so a commit that sets the pom version to a non-`-SNAPSHOT` value publishes a
release that never changes again.

This is deliberately deferred until the project is ready to cut a first real version;
until then `-SNAPSHOT` is the honest description of every build and the rolling release
is the whole story.

## Done when
- A push to `main` whose pom version has no `-SNAPSHOT` suffix creates a permanent
  release tagged `v<version>` (e.g. `v0.1.0`), not flagged as a pre-release, carrying the
  runnable jar.
- A push whose pom version still ends in `-SNAPSHOT` creates no permanent release, and
  the rolling `latest` release from #95 keeps behaving exactly as it does today.
- Re-running against a version already released fails loudly rather than silently
  overwriting or silently skipping — a released version is immutable.
- The route a human takes to cut a release is written down: which file to edit, and what
  happens on merge.

## Explicitly not
- Automating the version bump itself (changelog generation, semantic-release, or
  similar) — a human decides when a version is cut.
- Removing the rolling `latest` release — the two coexist.
- Publishing to Maven Central or building Docker images.

## Decisions made along the way
- none

## Deviations / notes
- none
