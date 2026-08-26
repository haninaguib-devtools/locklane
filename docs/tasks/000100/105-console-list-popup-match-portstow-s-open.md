# 105 — Console list popup: match portstow's Open Shells UX, fix stale count, hide when empty
Issue: #105

## Asked
Redesign the console list popup (`client/src/app/components/console-indicator/`) to
look and behave like portstow's "Open shells" popup: a modal-style popup with a
scrim, a focus trap, and keyboard navigation (arrow up/down, enter, escape), driven by
a reactive source rather than one-off HTTP calls into a manually-refreshed cached
field. Along the way, fix two bugs: the badge count doesn't update when a new console
opens (only on close), and the trigger button stays visible with zero open consoles.

## Done when
- The popup opens as a modal (scrim + an equivalent of `cdkTrapFocus`) and supports
  arrow-up/down, enter, and escape, matching portstow's `open-shells.ts` interaction
  pattern.
- Opening a new console immediately updates the badge count in the same session,
  without needing a close event or a `projectId` change to trigger a refresh.
  `ConsoleIndicatorComponent` is driven off a reactive source rather than a
  manually-refreshed cached field.
- The console indicator button in `app.component.html` does not render at all when
  zero consoles are open for the current project.
- Manual check: open the app, open a console, confirm the badge count updates
  immediately and the popup matches portstow's modal/keyboard behavior; close all
  consoles and confirm the button disappears.

## Explicitly not
No changes to the console/PTY backend, session model, or `ConsoleTabsComponent`'s
tab-switching behavior. No new `@angular/cdk` dependency — a small hand-rolled
focus trap stands in for `cdkTrapFocus`, since nothing else in this codebase pulls in
CDK yet and this task's scope doesn't cover `package.json`.

## Decisions made along the way
- `ConsolesService.onOpened`/`notifyOpened()` already exist (added by #108, which rode
  on this issue's declared scope of `consoles.service.ts` ahead of this task landing).
  This task only needed to wire `ConsoleIndicatorComponent` to the existing
  `onOpened`/`onClosed` observables via a reactive `entries` signal — no further
  service changes were needed.
- Kept the popup as a single self-contained component (trigger + modal), matching the
  existing structure of `console-indicator.component.ts` rather than splitting into a
  separate global picker service the way portstow does (`ShellPickerService` +
  `OpenShells`) — the issue's Scope names only the one component directory.

## Deviations / notes
- none
