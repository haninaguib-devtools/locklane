# 60 — Run the Angular unit tests in the client Maven module
Issue: #60

## Asked
`./mvnw -B test` claims to build and test every module, but the client module only
builds the Angular app: `client/pom.xml`'s frontend-maven-plugin runs `npm ci` and
`npm run build`, never the unit tests. CI (`.github/workflows/ci.yml`, the build
job) therefore merges client changes with the whole Angular test suite (currently
106 specs) unexecuted — task #58 shipped with its client specs verified only by a
manual local `ng test` run. Wire a headless test run into the client module's
Maven test phase so the AGENTS.md check set and CI actually cover the client.

## Done when
- `./mvnw -B test -pl client` runs the Angular unit tests headlessly (e.g.
  `ng test --watch=false --browsers=ChromeHeadless`) and fails the build when a
  spec fails.
- CI's existing `./mvnw -B test` job runs them without workflow changes (or the
  workflow is updated in the same PR if a browser install step is needed).
- A deliberately broken spec makes `./mvnw -B test` exit non-zero (verified once,
  then reverted).

## Explicitly not
- Changing `.github/workflows/ci.yml` unless CI proves it's actually needed
  (ubuntu-latest ships Chrome preinstalled).

## Decisions made along the way
- none yet

## Deviations / notes
- none
