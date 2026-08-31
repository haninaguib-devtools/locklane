# 447 — Open a shell at a console tab's worktree
Issue: #447 · Part of: #444

## Asked
Every open console tab — on both the issue console and the project console — gets
a second hover-revealed icon next to the existing close button (the same slot the
reveal-in-Finder icon uses, #441): open a shell rooted at that tab's exact
worktree. Clicking it creates a new shell session there and opens/focuses the
singleton Shells window on it, so a user tailing a log or running a one-off
command in the same directory as an agent console can do so without touching that
console's own PTY.

## Done when
- `console-tabs` renders a second hover/focus-revealed icon, distinct from the
  reveal-in-Finder one, on every tab backed by a real console (not the Overview
  pseudo-tab), on both the issue console and project console pages.
- Clicking it calls the shell-creation endpoint (#445) with that tab's worktree
  directory, then opens/focuses the Shells window (#446) navigated to the new
  shell.
- Clicking it again on the same tab always creates another new shell — no
  reuse/dedupe.
- A client test covers: the icon appears on a live console tab and is absent on
  the Overview tab; clicking it calls the creation endpoint with that tab's
  worktree path.
- `./mvnw -B test` and `./.t-workflow/scripts/consistency-check.sh` pass.

## Explicitly not
- The Shells window itself and the engine endpoints it calls — #445, #446.

## Decisions made along the way
- The owning project id is parsed from the tab's own session id (`^(\d+)-`) —
  the same convention the engine keys authorization and broadcasts on
  (`WorktreeSessionAuthorization`, `SessionRegistry`) — rather than injecting
  `CurrentProjectService` (agent, 2026-08-31): every real tab id carries it, and
  avoiding that service keeps this component free of its constructor-time
  `/api/projects` fetch, which would have broken the parent pages' existing
  specs (out of this task's scope) with an unexpected request.
- `ShellsService`/`WorktreesService` arrive as optional constructor parameters
  defaulting to null, not `inject()` fields (agent, 2026-08-31): the existing
  `console-tabs` spec instantiates the component with bare
  `new ConsoleTabsComponent()`, which `inject()` would break; Angular DI still
  fills declared constructor parameters normally, and the handler no-ops without
  them, so every existing test and call site is untouched.
- The tab's worktree directory resolves in two steps (agent, 2026-08-31):
  `tab.dir` when present — `ConsoleInfo` gains the optional field, and the
  project-console page's tabs already carry it at runtime because
  `labelProjectConsoles` spreads the page's own console objects — otherwise a
  lookup in `WorktreesService.list(projectId)` (the project worktree list, a
  read-only call with no side effects): first by exact `worktreeId`, then by the
  tab's issue number, since an issue has one worktree. The issue page's tabs
  carry no `dir` today (its `relabel()` maps ids and agents only, and changing
  it is outside this task's scope). Known limit, recorded deliberately: a
  reopened-conversation tab (`…-resume-…`) resolves to its issue's worktree row
  — the directory such a conversation runs in for every ordinarily-created
  console, but a conversation originally captured in a directory that no longer
  matches would get the issue worktree instead.
- `window.open('/shells/<id>', 'locklane-shells')` after creation — the
  initiative's own singleton convention: a repeated open with the same window
  name navigates and focuses the existing window instead of stacking a new one.

## Deviations / notes
- none
