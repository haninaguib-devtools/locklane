# 640 — Cache Node/npm and node_modules in the CI build
Issue: #640

## Asked
CI's Maven build (`.github/workflows/ci.yml`) already caches `~/.m2/repository` via
`actions/setup-java`'s `cache: maven`. But the client module's build
(`client/pom.xml`) uses `frontend-maven-plugin`, which on every CI run downloads a
fresh isolated Node v22.13.0/npm 10.9.2 binary into `client/node/` and then runs
`npm ci`, redownloading every npm package into `client/node_modules/` from scratch.
Neither directory is cached today, unlike a developer's machine where both persist
between builds — this is the CI-vs-local speed gap. Cache them the same way the Maven
dependencies already are.

## Done when
- `.github/workflows/ci.yml` has an `actions/cache` step, before the "Build and test
  every module" step, that caches `client/node` and `client/node_modules`.
- The cache key is derived from `hashFiles('client/package-lock.json', 'client/pom.xml')`
  (the pom hash covers a Node/npm version bump in the plugin config, since those
  versions live in `client/pom.xml`'s `<properties>`).
- The cache step only runs when the build itself runs (mirrors the existing
  `steps.build-inputs.outputs.run == 'true'` gate on `setup-java` and the Maven build
  step), so a skipped build doesn't restore or save a cache pointlessly.
- A CI run after this change (e.g. this task's own PR) shows a cache hit on a second
  run with an unchanged lockfile, and `./mvnw -B test` still passes.

## Explicitly not
- Caching `~/.m2/repository` — already handled by `setup-java`'s `cache: maven`.
- Any change to `client/pom.xml` or the frontend-maven-plugin configuration itself.

## Decisions made along the way
- The new step lands inside the existing `# <!-- local -->` … `# <!-- /local -->` slot
  at the end of the `checks` job's `steps:` list (`docs/architecture/local-slots.md`),
  immediately before "Build and test every module" — the only place a consumer-local
  addition to `ci.yml` is allowed to land. (claude, 2026-09-03)

## Deviations / notes
- none
