# Sonar triage — the morning routine

The `Sonar triage` workflow runs nightly (05:00 UTC) and on manual dispatch from
the Actions tab. It fetches open SonarQube issues for project `locklane`
(excluding anything tagged `tracked`), has an agent recommend **fix**, **accept**,
or **false positive** for each one, and publishes the table to the run's job
summary plus a `sonar-triage-table` artifact. The `Sonar` scan itself stays
informational: nothing here gates merges.

## Each morning

1. Open the latest `Sonar triage` run (Actions → Sonar triage) and read the job
   summary table — or download the `sonar-triage-table` artifact for the same
   table as a file. "No open Sonar issues" means the backlog is empty.
2. For each row:
   - **fix** — file a task (or fix it directly) and tag the Sonar issue `tracked`
     once it is represented in the tracker, so tomorrow's report stops listing it.
   - **accept** — triage it in SonarQube as accepted/won't-fix and tag it
     `tracked` so it leaves the report.
   - **false positive** — mark it false-positive in SonarQube; the tag follows
     the resolution out of the open backlog.
3. If a run is green with a non-empty backlog but no table, treat it as broken:
   the workflow is supposed to fail loudly in that case (a missing table with a
   non-empty backlog exits 1 instead of reporting all-clear) — investigate the
   `Triage with Claude` step before trusting the summary.

## Manually

- Dispatch `Sonar triage` from the Actions tab any time; it finishes green with
  the same summary table and artifact.
- The fetch script doubles as a local probe (needs `SONAR_HOST_URL` and
  `SONAR_TOKEN` in the environment):
  `.github/scripts/sonar-triage-fetch.sh` prints `{sonarHost, projectKey,
  issues}` JSON for project `locklane`, paging past 100 issues.
- Sonar links in the table come from the `SONAR_HOST` repository variable
  (`vars.SONAR_HOST`), not the legacy `SONAR_HOST` secret, so they render
  unmasked.
