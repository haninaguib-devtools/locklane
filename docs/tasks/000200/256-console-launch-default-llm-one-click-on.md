# 256 — Console launch: default-LLM one-click on both +, move shell launch to projects page
Issue: #256

## Asked
Starting a console from a project asks the user which LLM to run, but only inconsistently:
the sidenav's "+" beside a project name already skips that question and opens a console
with whatever LLM is set as the default in Settings. The Project console page does not —
when a project has zero open consoles it shows an inline "which LLM do you want" picker
(claude/codex/shell) before it will start one, and the page's own "+" (shown once consoles
already exist, in the console tab strip) pops the same picker again. This task makes the
Project console page's "+" behave exactly like the sidenav's: one click, default LLM, no
picker, no way to land on a shell from either "+". Because that removes the only way to
start a non-LLM shell console for a project, this task also adds a new "open a shell"
action on the projects list page (no LLM involved at all) so that capability isn't lost —
just relocated to a single, explicit place.

## Done when
- The Project console page, when a project has zero open consoles, no longer shows the
  agent/LLM picker UI or a separate "start" button. Landing on the page in that state
  starts a console immediately using the same default-agent source the sidenav "+" uses
  (`DefaultAgentStore`), then shows the console like the sidenav flow does.
- The "+" in the Project console page's console tab strip (`console-tabs` component, the
  project-console call site specifically — `locationChoice=false`) no longer opens the
  agent-picker popover; it starts a new console with the default agent directly, matching
  the sidenav's `openNewConsole`.
- The sidenav "+" is unchanged (already matches this behavior) — a manual check confirms
  no regression.
- The projects list page (`overview` component) gains a button/action, per project row,
  that opens a console tagged with the `'shell'` agent — no LLM picker, no default-agent
  involvement. It navigates to that project's console the same way the other entry points
  do.
- Existing unit tests for `project-console`, `console-tabs`, and `overview` are updated
  for the new behavior; new tests cover: (a) the project-console empty state starts a
  default-agent console with no picker rendered, (b) the overview page's new button starts
  a console with agent `'shell'`.
- `./mvnw -B test` and `./scripts/consistency-check.sh` pass.

## Explicitly not
- `AgentPickerComponent` itself, and any other call site that still uses it with
  `locationChoice=true` (worktree/issue-level console launches, if any), are unchanged.
- `project-summary.component`'s existing "Open console" button is unchanged; the new
  no-LLM shell action lives only on the projects list (overview) page.
- `DefaultAgentStore` stays `'claude' | 'codex'` only — no change to what counts as a
  default LLM, and no new Settings option.

## Decisions made along the way
- Closing the project console page's last remaining open console now also
  auto-starts a fresh default-agent console (same as landing on the page with
  none open), instead of falling back to the removed picker/start button —
  otherwise the page would have no way out of the zero-console state at all
  (implementer, 2026-08-27).

## Deviations / notes
- Touched `client/src/app/app.component.spec.ts`, outside the issue's declared Scope
  line: two of its integration tests modeled the project-console page's "zero open
  consoles" state by flushing an empty session list, which now (correctly) triggers
  the new auto-start and its `notifyOpened()` side effects (extra requests from the
  header console-indicator and the sidenav). Updated those two tests to flush the
  new requests / model an already-open session instead of leaving them broken by
  the intended behavior change (implementer, 2026-08-27).
