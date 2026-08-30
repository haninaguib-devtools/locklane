# 399 — Add a manually-triggered Sonar scan with test coverage
Issue: #399

## Asked
Give the project an on-demand code-quality and test-coverage report. Nothing measured
how much of the code the tests exercise, and there was no quality dashboard at all.
This adds a **Sonar** GitHub Action a maintainer starts by hand from the Actions tab —
it builds the project, measures test coverage, and uploads both to the project's
SonarQube server. It never runs on its own: no push, no pull request, no schedule. Every
existing automated check is left exactly as it is, so turning this on cannot make a pull
request start failing.

## Done when
1. `.github/workflows/sonar.yml` exists, named `Sonar`, its `on:` block containing
   `workflow_dispatch` and nothing else.
2. No other file under `.github/` is modified by this diff; the status-check contexts in
   `.t-workflow/scripts/github-bootstrap.sh` are unchanged, so branch protection is untouched.
3. JaCoCo is configured in the root `pom.xml` so `./mvnw -B verify` produces an XML
   coverage report per module, readable by Sonar from a single configured path.
4. The existing `build` job still passes unchanged (`./mvnw -B test`), and
   `./.t-workflow/scripts/consistency-check.sh` passes.
5. The scan job runs `./mvnw -B verify sonar:sonar` with `sonar.projectKey=locklane`,
   reading `SONAR_HOST` and `SONAR_TOKEN` from repository secrets (no `environment:` key).
6. Checkout uses `fetch-depth: 0`, Java 21 via `actions/setup-java` with Maven caching,
   every action pinned by commit SHA, matching `.github/workflows/ci.yml`.
7. Human-judged: a maintainer triggers the workflow once and confirms the `locklane`
   project appears on the SonarQube server with a non-zero coverage figure.

## Explicitly not
- No automatic triggering of any kind, now or later.
- No pull-request decoration on SonarQube (`sonar.pullrequest.*`).
- No coverage threshold, quality gate enforcement, or build failure on Sonar results.
- No changes to `ci.yml`, `pages.yml`, `release.yml`, `review-gate.yml`, or `stale-branches.yml`.

## Decisions made along the way
- Coverage is produced by the ordinary per-module JaCoCo `report` goal writing
  `target/site/jacoco/jacoco.xml`, and Sonar is pointed at them through the single
  `sonar.coverage.jacoco.xmlReportPaths` property set in the root `pom.xml`. A dedicated
  aggregator module (the other way to get one aggregate file) would have meant a new
  module outside this task's Allowed paths — the plan named this trade-off in advance.
- `client` holds no Java (Angular, built by `frontend-maven-plugin`), so it produces no
  coverage; JaCoCo's `report` goal skips quietly when a module has no execution data.
- The JaCoCo and Sonar plugin versions are pinned literally in the root `pom.xml`, in
  keeping with how the repo pins every other plugin version.

## Deviations / notes
- none
