# 97 — Collapse the duplicated project version into one place

Issue: #97 · Part of: #95

## Asked
The project's version number was written down twice — the root `pom.xml` and
`engine/pom.xml` each declared `0.1.0-SNAPSHOT` independently, because `engine` parented
to `spring-boot-starter-parent` rather than to the root aggregator, so a bump to one could
silently leave the other behind. This task makes the version live in exactly one file.

## Done when
- The version string `0.1.0-SNAPSHOT` appears in exactly one `pom.xml` in the repo:
  `grep -rl '0\.1\.0-SNAPSHOT' --include=pom.xml .` returns one path.
- `./mvnw -B test` passes, and `engine` still builds against the same Spring Boot
  dependency versions it used before.
- Bumping the version means editing one file, and the jar filename, reactor-reported
  version, and any release naming all follow from that edit.

## Explicitly not
- Changing the version itself, or introducing automated version bumping.
- Any release or packaging behaviour change.

## Decisions made along the way
- `engine/pom.xml` now parents to the root aggregator (`dev.locklane:locklane`) instead
  of `spring-boot-starter-parent`, and omits its own `<version>` — Maven's parent-version
  inference (available since Maven 3.5.0, confirmed available here: Maven 3.9.9) resolves
  it from the parent via the default `relativePath` (`../pom.xml`). `client/pom.xml`
  already omitted its own `<version>`; only its redundant `<parent><version>` was removed
  the same way. (haninaguib, 2026-08-26)
- The root `pom.xml` now imports `spring-boot-dependencies:3.5.4` as a BOM in
  `<dependencyManagement>`, so `engine` keeps the same managed dependency versions it had
  through `spring-boot-starter-parent` → `spring-boot-dependencies`. BOM import only
  carries `<dependencyManagement>`, not `<build><pluginManagement>`, so two things that
  `spring-boot-starter-parent` provided are now declared explicitly in `engine/pom.xml`
  instead of inherited: the `spring-boot-maven-plugin` version and its `repackage`
  execution (both `3.5.4`, matching what `spring-boot-starter-parent` pinned), and
  `maven.compiler.release`/`project.build.sourceEncoding` (previously set from
  `spring-boot-starter-parent`'s own properties). (haninaguib, 2026-08-26)

## Deviations / notes
- Checked whether the lost `spring-boot-starter-parent` resource filtering (for
  `application*.yml`) mattered: `engine/src/main/resources/application.yml`'s `${...}`
  placeholders (e.g. `${user.home}`) are resolved by Spring's own property resolution at
  runtime, not Maven build-time filtering, so dropping that filtering config is safe.
  — none otherwise.
