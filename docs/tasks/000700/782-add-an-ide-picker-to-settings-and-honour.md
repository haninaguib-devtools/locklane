# 782 — Add an IDE picker to Settings and honour it from Open IDE
Issue: #782 · Part of: #780

## Asked
Let a person choose which editor "Open IDE" uses. The settings dialog gains an "IDE"
section shaped like "Default agent", listing what the engine reports as installed
(`GET /api/ides/installed`, from sibling #781). Desktop IDEs (VS Code, IntelliJ IDEA) are
offered only when the page is on `localhost` — the same `isLocalHost` gate "Folder" uses —
so over the network only code-server remains, and the section is not shown at all when
there is nothing to choose between. The agent tab's menu item then does what was chosen:
for code-server it opens the returned URL in a browser tab exactly as today; for a desktop
IDE the engine launches the editor and there is nothing for the browser to open. The
item's label names the choice, `Open in VS Code` / `Open in IntelliJ IDEA`, and stays
`Open IDE` when code-server is chosen. The preference is a per-browser setting stored in
`localStorage`, like the default agent, since "local" means the browser and the engine
share the machine.

## Done when
- A new `DefaultIdeStore` (`client/src/app/services/default-ide-store.ts` + spec), shaped
  like `DefaultAgentStore`: a signal-backed choice persisted under `locklane.defaultIde`,
  an on-demand one-per-app-load fetch of `GET /api/ides/installed`, and an *effective*
  choice that falls back to `code-server` whenever the stored id is not installed or is a
  desktop IDE on a non-`localhost` page. The spec covers the fallback in both cases.
- The settings dialog renders an "IDE" section with one button per option available to
  this browser (all installed entries on `localhost`; only non-desktop ones elsewhere),
  and renders no section when only one option is available. Spec covers local and remote.
- `ConsolesService.openIde(projectId, id, ide)` posts `{ "ide": "<id>" }`;
  `OpenedIde.url` becomes `string | null`.
- `ConsoleTabsComponent`: the menu item label follows the effective choice as described;
  on success it calls `window.open` only when the returned URL is non-null; a failure
  shows the existing `ide-error` line. Spec covers the label, the code-server path, the
  desktop path, and the error.
- Client specs pass and `./mvnw -B test` passes.

## Explicitly not
- Engine changes — the sibling engine task #781 in this initiative, which this task is
  blocked by.
- Any change to "Folder" or its `localhost` gate.
- Persisting the choice server-side.

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- The `localhost` gate lives in `DefaultIdeStore` (`isLocalHost`, reading
  `window.location.hostname` through an overridable `currentHostname()` exactly as
  `ConsoleTabsComponent` does for "Folder"), so the settings dialog's "available to this
  browser" list and the tab strip's effective choice are one computation
  (`available()` / `effective()`); "Folder" keeps its own untouched gate (agent, 2026-09-07).
- `effective()` returns the whole installed entry (id, label, desktop), with a constant
  code-server entry as the fallback, so the tab strip derives its label
  (`Open in <label>` for a desktop IDE, `Open IDE` otherwise) without knowing any IDE's
  name itself. Until the installed fetch resolves, nothing is installed as far as the
  store knows, so the effective choice is code-server and the label is `Open IDE` —
  exactly the pre-#782 behaviour (agent, 2026-09-07).
- `ConsoleTabsComponent` takes `DefaultIdeStore` as a fourth `@Optional()` constructor
  parameter, the same convention as its other services (#447), so the existing bare
  `new ConsoleTabsComponent()` specs keep working; with no store it posts `code-server`
  (agent, 2026-09-07).
- The strip asks for the installed set in `ngOnInit` **only when a preference is
  stored**. With nothing chosen the effective choice is code-server whatever is
  installed — the pre-#782 behaviour — so there is nothing to look up; a stored choice
  needs the list to know whether it still holds. This is also what keeps the change
  inside the Scope line: the `main-content` and `project-console` specs (outside Scope)
  render the strip — and open its tab menu — under an `HttpTestingController` whose
  `afterEach` verifies no request is left open, so an unconditional fetch on init or on
  menu open would have failed them (agent, 2026-09-07).
- The settings dialog's IDE buttons use their own `ide-toggle` / `ide-option` classes
  (sharing the agent buttons' CSS rules) rather than reusing `agent-option`, which the
  existing specs count (agent, 2026-09-07).
- The dialog marks the *effective* choice as chosen, not the raw stored id, so a stored
  desktop IDE viewed from a remote page — or a stored id no longer installed — shows
  code-server as what "Open IDE" will actually do (agent, 2026-09-07).

## Deviations / notes
- Driven child of #780: the blocker gate (`check-blocker-gate.sh --siblings 780 <file>`)
  was run by the driver with the sibling dispositions file — exit 0, #781's PR #788 merged
  into `wip/780-integration` with a `readiness: ready` review, "satisfied by its driven
  merge" (ADR-009 D1) — not by this session. #781's issue stays open until the aggregate
  PR reaches the trunk (ADR-004 Decision 3). Branch cut from `wip/780-integration`; every
  trunk diff here is against `origin/wip/780-integration`.
- **`./mvnw -B test` FAILS on one spec outside this task's Scope line, and the fix was not
  made here.** `client/src/app/app.component.spec.ts` ("opens the settings dialog from
  the menu and closes it again") opens the real settings dialog under an `afterEach`
  that verifies no HTTP request is left open; the dialog now fetches
  `GET /api/ides/installed` when it opens — which the "IDE" section requires, since it
  cannot list options it never fetched — so that test ends with one open request. The
  other 830 specs and every engine test pass. The fix is one line in that spec, next to
  its existing `/api/account/2fa/status` flush:
  `httpMock.expectOne('/api/ides/installed').flush({ installed: [] });` — but the file is
  not in Scope, and the driver's instruction for a driven child is to stop and report
  rather than edit outside it. Left for the human: widen #782's Scope line with
  `client/src/app/app.component.spec.ts` (or make that edit in the initiative), then
  re-run (agent, 2026-09-07).
- A direct `npm run test:ci` of the whole client suite (not the recorded check) once hung
  in this headless console environment — ten failures across `EventsService` foreground
  listeners, `TerminalComponent` and `ShellsWindowComponent`, then a 30-second browser
  disconnect at 674 of 831. None of those import anything this task touched, all 71 of
  their specs pass when run in isolation, and the Maven run afterwards executed the whole
  suite with only the `AppComponent` failure above. Noted as an environment flake, not
  acted on (agent, 2026-09-07).
