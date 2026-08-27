# 196 — Remove legacy project-root bootstrapping
Issue: #196

## Asked
On every engine startup, `ProjectBootstrapper` silently registers the engine's own
checkout (`locklane.project-root`, defaulted to `${user.dir}/..`) as a `Project`. That
was a stand-in from before Locklane supported multiple projects, each with its own
`workarea_path` — the general mechanism now covers what this one-off bootstrapping used
to be for, so the auto-registration should go. A fresh install should show an empty
sidenav until a user explicitly adds a project.

## Done when
- A fresh engine startup registers no project on its own — the sidenav is empty until a
  user explicitly adds one via "+ add project".
- `locklane.project-root` no longer appears anywhere in the repo (`application.yml` main
  and test config, code, or docs) — `grep -r "project-root"` returns nothing.
- `ProjectBootstrapper`'s self-registration behavior is removed (the class deleted
  entirely, since nothing else in it survives).
- `IssueDetailService` resolves Locklane's own task-record paths through some other means
  that doesn't depend on the removed property.
- `./mvnw -B test` passes.

## Explicitly not
- Building a project-picker/projects-list UI page — a separate, already-known gap
  (#44/#45), not part of this task.

## Decisions made along the way
- `IssueDetailService` already took its `projectRoot` from `project.workareaPath()`
  (since #81) rather than reading `locklane.project-root` directly — that direct
  dependency was already removed in an earlier task. So this task's only remaining
  code changes are deleting `ProjectBootstrapper` (and its test) and the property itself;
  once a user adds Locklane's own checkout as a project via "+ add project", its
  `workarea_path` makes `IssueDetailService` resolve this repo's own `docs/tasks/`
  records with no further code change (haninaguib, 2026-08-27).

## Deviations / notes
- The issue's "Done when" grep criterion is read as covering current, living docs —
  historical task records under `docs/tasks/000000/` (16, 42, 81) mention
  `locklane.project-root` as an accurate account of decisions made at the time and are
  left untouched: they are outside this issue's Scope line, and rewriting a merged
  record to match a later change would falsify the history it exists to preserve
  (`docs/tasks/README.md`). `grep -r "project-root"` therefore still finds those three
  historical files after this task; everything current (config, code) is clean.
