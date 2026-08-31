# 448 — Open a shell at a project's main checkout from the Shells sidenav
Issue: #448 · Part of: #444

## Asked
Inside the Shells window, each project's section in the sidenav gets a `+` —
mirroring the project console's own `+` — that creates a new shell rooted at that
project's main checkout (its base clone directory, the same one the project
console's own `main` sessions already run in) and selects it, with no dedupe:
clicking it twice makes two shells.

## Done when
- Each project section in the Shells window's sidenav (#446) has a `+` control.
- Clicking it calls the shell-creation endpoint (#445) with that project's base
  checkout path (no issue, no worktree) and selects the new shell in the content
  area.
- Clicking it again always creates another new shell.
- A client test covers: clicking `+` creates a shell targeting the project's base
  path and selects it.
- `./mvnw -B test` and `./.t-workflow/scripts/consistency-check.sh` pass.

## Explicitly not
- An equivalent `+` at the issue level inside the Shells sidenav — issue-level
  shells are opened from console tabs (#447), not minted from within this window.

## Decisions made along the way
- `ShellsService` gains `open(projectId, issueNumber, workingDirectory)` — the
  client side of #445's `POST /api/projects/{projectId}/shells` (agent,
  2026-08-31). Added here rather than in #447 deliberately: this task's scope is
  `client/src/app/` broadly, while #447's is pinned to the console-tabs component
  directory, so the shared creation call has to land from this task for #447 to
  be implementable inside its own scope.
- The `+` renders only on a project section whose project is known to the
  projects list (agent, 2026-08-31): the base checkout path comes from
  `Project.workareaPath`, which the sidenav already receives for group names, so
  a group rendered under the `project <id>` fallback (its project missing from
  the list) has no path to mint at and shows no `+` rather than a button that
  cannot work.
- Sections exist only for projects that already have shells (#446's grouping is
  built from the listing), so the `+` cannot mint a project's very first shell
  from this window (agent, 2026-08-31) — consistent with the issue's own framing
  ("each project's section … gets a `+`"); the first shell for a fresh project
  comes from a console tab (#447) or a later affordance.
- The sidenav emits the whole `Project` on `+` and `ShellsWindowComponent` does
  the minting (agent, 2026-08-31): creation, selection, and the listing re-fetch
  all live where selection state already lives; the sidenav stays the pure
  presenter it was in #446. On success the window re-fetches, sets the new shell
  selected, and navigates to `/shells/:id` — no dedupe anywhere, every click
  mints.

## Deviations / notes
- none
