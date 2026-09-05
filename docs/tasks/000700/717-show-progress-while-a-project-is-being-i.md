# 717 — Show progress while a project is being imported or created
Issue: #717

## Asked
Importing an existing repo or creating a new one feels dead while the background clone runs. Make the wait visibly alive, client-side only: a locked submitting dialog with spinner and staged hints, a sidebar row for the new import that flashes into view and pulses with elapsed time, and the same spinner plus elapsed treatment on the project console waiting state.

## Done when
- Pressing add/create disables the dialog's submit, mode tabs, inputs, close button, backdrop click, and Escape while the request is in flight; the submit button shows a spinner and the dialog shows a staged hint that cycles contact GitHub, clone repository, prepare workarea.
- After an import succeeds, the sidebar reveals the new project row (expanded, scrolled into view, briefly highlighted) showing a pulsing cloning indicator plus a staged line and an elapsed-seconds counter that ticks up while status is CLONING.
- After a create succeeds, the console page's waiting state shows the same spinner, staged hint, and elapsed counter instead of static text, and keeps polling to READY/FAILED as today.
- A human judges the import and create waits as visibly active rather than dead; existing client suites still pass.

## Explicitly not
- No engine change: the CLONING/READY/FAILED status model stays as is; staged hints are estimated client text, not server-reported steps.
- No full navigate-to-project for imports; the import stays on the current page with the new row revealed in place.

## Decisions made along the way
- Staged hints derive from elapsed seconds (shared `cloneStageHint` helper), not a rotating timer: deterministic, testable, identical wording in dialog, sidenav, and console.
- `AppComponent.onProjectCreated` takes the created `Project` and always reveals it in the sidenav (branchless: both import and create responses are CLONING; a READY response highlighting briefly is harmless).

## Deviations / notes
- Dialog handoff (human's call, 2026-09-05): the first cut closed the import dialog on POST success, leaving a 2-3s dead gap before the sidebar row appeared after its full-list refresh -- the human saw exactly that locally and rejected it. Now the import dialog stays open, locked with its timer running, emitting `imported` instead of `created`; the host closes it from the reveal's `done`, which also fires when the reload fails so the dialog can never trap. `created` is now create-mode only. Verified live against the local dev instance (compose-manager import).
- Overview settles cloning rows (human's call, 2026-09-05): importing from the overview page left the new row stuck on CLONING -- that page never re-read, unlike the sidenav and console page. It now re-checks on the same 3s cadence until every row settles, with quiet re-reads that never flash the loading state.
