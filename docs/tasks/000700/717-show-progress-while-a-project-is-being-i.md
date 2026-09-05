# 717 — Show progress while a project is being imported or created
Issue: #717

## Asked
Importing an existing repo or creating a new one feels dead while the background clone
runs: the add-project dialog only swaps its button to adding/creating, then closes
(import) or lands on a static line (create), with no motion, elapsed time, or staging.
This task makes the wait visibly alive, client-side only: a locked submitting dialog
with spinner and staged hints, a sidebar row for the new import that flashes into view
and pulses with elapsed time, and the same spinner plus elapsed treatment on the project
console waiting state.

## Done when
- Pressing add/create disables the dialog's submit, mode tabs, inputs, close button,
  backdrop click, and Escape while the request is in flight; the submit button shows a
  spinner and the dialog shows a staged hint that cycles contact GitHub, clone
  repository, prepare workarea.
- After an import succeeds, the sidebar reveals the new project row (expanded, scrolled
  into view, briefly highlighted) showing a pulsing cloning indicator plus a staged line
  and an elapsed-seconds counter that ticks up while status is CLONING.
- After a create succeeds, the console page's waiting state shows the same spinner,
  staged hint, and elapsed counter instead of static text, and keeps polling to
  READY/FAILED as today.
- A human judges the import and create waits as visibly active rather than dead;
  existing client suites still pass.

## Explicitly not
- No engine change: the CLONING/READY/FAILED status model stays as is; staged hints are
  estimated client text, not server-reported steps.
- No full navigate-to-project for imports; the import stays on the current page with the
  new row revealed in place.

## Decisions made along the way
- Driven on a separate branch `claude-717` (not the pipeline's default `wip/717-...`) at
  the human's explicit request, alongside an already-open PR #718 for the same issue from
  another session (haninaguib, 2026-09-05) — an independent second implementation, not a
  continuation of #718.
- The staged hint and elapsed-seconds helper (`cloneStageHint`/`elapsedSeconds`) lives in
  `add-project-popup/clone-progress.ts` and is imported by `sidenav` and
  `project-console`, rather than duplicated three times or hoisted outside the task's
  declared scope directories.
- The sidebar's "reveal the new project row" behavior is driven by `sidenav` diffing the
  project ids its own last load returned against the current one — a ready-made way to
  detect "a project new to this session's view" entirely inside `sidenav`'s own scope,
  without widening scope onto `app.component.ts` to pass the new project's id through the
  `created` event (which is not in this task's declared scope).
- Found and fixed a pre-existing latent bug while making "inputs disabled while
  submitting" actually true: every `add-project-popup` field that combines
  `[(ngModel)]` with a plain `[disabled]="submitting"` binding never actually disabled
  the native element — Angular's forms directive on that element resets the DOM
  `disabled` *property* each change-detection run, fighting the property binding. Fixed
  by switching those bindings to `[attr.disabled]="submitting ? '' : null"` (an
  *attribute* binding, which the forms directive does not contend for) on every such
  field: `gitUrl`, `name`, `org`, `newRepoName`, the template and GitHub-account
  selects, and the bootstrap checkbox. Confirmed via a failing test before the fix and
  a passing one after (`input[name="gitUrl"]` truly `.disabled` while submitting).

## Deviations / notes
- none
