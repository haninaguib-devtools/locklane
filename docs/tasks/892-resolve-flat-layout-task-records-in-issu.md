# 892 — Resolve flat-layout task records in issue detail lookup
Issue: #892

## Asked
The issue overview page's "record" row always reads "no record yet" even when the task record exists. The engine's `IssueDetailService.recordPath()` only searches legacy bucket subdirectories (`docs/tasks/<bucket>/`) for `<number>-*.md` and never the flat location `docs/tasks/<id>-<slug>.md` that the workflow actually creates, so every current record resolves to null.

## Done when
- `GET /api/issues/{number}/detail` returns `recordPath` for an issue whose record lives at `docs/tasks/<id>-<slug>.md`, still resolves legacy bucketed records (`docs/tasks/<bucket>/<id>-*.md`), and returns null only when no record exists in either place.
- Engine tests cover both layouts (a new flat-layout case alongside the existing bucket test in `IssueDetailServiceTest`).
- `scripts/check.sh` passes.

## Explicitly not
- No client/UI changes: the overview tab already renders whatever `recordPath` the backend returns.
- No migration of legacy bucketed records to the flat layout.

## Decisions made along the way
- none

## Deviations / notes
- none

## Agents
- work: opencode / meta/muse-spark-1.3-contributor
