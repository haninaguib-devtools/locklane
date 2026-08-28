# 220 — Simplify issue-page + to worktree/main only, using default agent
Issue: #220 · Part of: #218

## Asked
The issue page's "+" button (`ConsoleTabsComponent`) currently asks two things —
worktree vs main, and which agent (Claude/Codex/shell) — then requires a separate
"Open" click to confirm. Now that a default agent lives in Settings (#219), the agent
question and the extra click are redundant for that flow: the button should only ask
worktree vs main, and launch the console immediately on that choice, using the
Settings default agent.

## Done when
- The "+" picker in `client/src/app/components/console-tabs/` offers only worktree vs
  main (no agent picker) when used from the issue page.
- Selecting worktree or main starts the console immediately — no separate "Open"
  button/click.
- The console is launched with the default agent read from the setting added in #219,
  not a hardcoded `'claude'`.
- `main-content.component.ts`'s `openConsole` (and `issuesService.startSession`) still
  receive a valid agent value with this wiring in place.
- A human confirms in the browser that clicking worktree or main on an issue page opens
  a console with the Settings-selected agent.

## Explicitly not
- Any per-session agent override (e.g. a "convert to Codex" menu on an existing
  console) — explicitly out of scope per the issue's Non-goals.
- Changing the project-console page's own "+" (`locationChoice=false`), which still
  needs its own claude/codex/shell picker — `DefaultAgentStore` only models
  claude/codex, not shell, so that flow keeps `AgentPickerComponent` unchanged.

## Decisions made along the way
- `ConsoleTabsComponent` gets a new `defaultAgent` input rather than injecting
  `DefaultAgentStore` itself: the component's existing spec constructs it with `new
  ConsoleTabsComponent()` outside Angular's DI, and its own injection would break that.
  `MainContentComponent` (already the place doing agent-launch wiring) injects the
  store and binds `[defaultAgent]="defaultAgentStore.agent()"` — matching the issue's
  own scope note that `main-content.component.ts`'s wiring is part of this task
  (haninaguib, 2026-08-27).
- Kept `AgentPickerComponent` and the `locationChoice=false` picker (used by
  `ProjectConsoleComponent`'s tab-strip "+") entirely unchanged: that flow still needs
  the claude/codex/**shell** choice, which the default-agent setting (claude/codex
  only) cannot represent (haninaguib, 2026-08-27).
- Added a `chooseLocation(location)` method that emits `open` and closes the picker
  immediately, alongside the existing `confirmOpen()` used by the unchanged
  locationChoice=false flow, rather than collapsing them into one method (haninaguib,
  2026-08-27).

## Deviations / notes
- none
