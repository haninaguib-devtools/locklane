# 26 — Bundle the Angular client into the engine's fat jar

Issue: #26 · Part of: #1

## Asked
Add a `client` Maven module wrapping the existing Angular project
(frontend-maven-plugin to install Node/npm and run `ng build`), wire the root
`pom.xml`'s `<modules>` to include it, and have the built `dist` output copied into the
engine module's static resources so the fat jar produced by a full build serves the
Angular app itself — no separate `ng serve` needed. Also add Spring Boot DevTools to
`engine/` so the backend auto-restarts on `engine/**` source changes during local
development.

## Done when
- A single `./mvnw package` produces a runnable engine jar that serves the Angular UI at
  `/` when run standalone (`java -jar engine/target/*.jar`), with the client's static
  assets bundled in the jar itself.
- `./mvnw -B test` still passes unchanged in behavior, now also covering the new
  `client` module in the reactor.
- Running the engine locally in dev mode (`./mvnw -pl engine spring-boot:run`), editing
  and saving an `engine/**` source file triggers an automatic restart (Spring Boot
  DevTools).

## Explicitly not
- Any change to `.github/workflows/ci.yml` unless the build genuinely requires it.
- Packaging or deployment beyond the runnable jar itself.
- Frontend hot-reload/live-reload (already handled by `ng serve`).

## Decisions made along the way
- none

## Deviations / notes
- none
