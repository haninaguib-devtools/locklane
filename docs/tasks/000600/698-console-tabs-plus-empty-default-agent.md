# 698 — console-tabs '+' can launch with an empty default agent before Settings/project-summary have fetched the installed list
Issue: #698

## Asked
Locklane's Settings dialog and the project summary page's "Open console" button both
learn the installed-agents list before they let a launch happen (task #695), so they
always launch with a real CLI as the default agent. The console tab strip's own "+"
button (`console-tabs.component.ts`, used from `main-content.component.ts` on an issue
page and `project-console.component.ts` on the project-console page) reads the same
`DefaultAgentStore.agent()` value but neither of those two page components ever calls
`DefaultAgentStore.refreshInstalled()` themselves. On a fresh browser session that lands
directly on an issue page or the project-console page -- without visiting Settings or
the project summary page first -- clicking "+" can launch a console with `agent: ''`
instead of a real CLI, because the store's correction to the first installed agent only
takes effect once some caller's fetch has resolved.

## Done when
- `main-content.component.ts` and `project-console.component.ts` each call
  `defaultAgentStore.refreshInstalled()` from their own initialization (`ngOnInit`),
  the same fix already applied to `project-summary.component.ts` in #695.
- A new or updated test in each of `main-content.component.spec.ts` and
  `project-console.component.spec.ts` demonstrates the "+" button no longer launches
  with an empty agent on a page that never visited Settings or the project summary
  first.
- Every existing spec that constructs either component (including `app.component.spec.ts`,
  which renders both through real routing) still passes -- each now owes the new
  `GET /api/agents/installed` request a flushed response, since `HttpTestingController`
  fails a test that leaves any request unflushed.
- `./mvnw -B test` and the client test suite (`npm run test:ci`) pass.

## Explicitly not
- No change to `DefaultAgentStore` itself, or to `settings-dialog.component.ts` /
  `project-summary.component.ts`, which already call `refreshInstalled()`.
- No change to what agent a console actually launches with once the fetch has
  resolved -- only to when that fetch is triggered.

## Decisions made along the way
- none

## Deviations / notes
- none
