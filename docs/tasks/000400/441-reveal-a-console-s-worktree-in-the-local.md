# 441 — Reveal a console's worktree in the local file manager
Issue: #441

## Asked
When locklane runs locally, add a way to open a console's worktree in the OS's native
file browser (Finder on macOS, Explorer on Windows, whatever the default file manager is
on Linux) directly from the console tab strip. The action lives on the tab itself: a
small icon that fades in on hover/focus next to the existing close (×) button, on every
tab backed by a real console — both on the issue console (`main-content`) and the
project console (`project-console`) pages. The pinned Overview pseudo-tab on the issue
console has no worktree behind it and never gets the icon.

The engine already knows each console's filesystem path (the same place PTY spawning
gets its cwd), so the client never sends a path — it calls the engine with the console's
id, and the engine resolves the path and shells out to the platform's reveal command
itself. This only works when the browser and the engine are on the same machine, which
is the normal local-dev usage pattern for locklane today.

## Done when
- A new engine endpoint (e.g. `POST /consoles/{id}/reveal-in-file-manager`) looks up
  that console's worktree path server-side and launches the OS file manager at it:
  `open <path>` on macOS, `explorer.exe <path>` on Windows, `xdg-open <path>` on Linux.
- The endpoint takes only a console id — never a client-supplied path — so it can't be
  used to open an arbitrary path.
- `console-tabs` renders a hover/focus-revealed reveal icon next to the close button on
  every tab with a live console, on both the issue console and project console pages; it
  does not render on the Overview pseudo-tab.
- Clicking the icon calls the new endpoint for that tab's console id.
- An engine test covers: the endpoint resolves the correct path for a console id, and
  invokes the expected per-OS command (via an injectable process runner, not a real
  subprocess in the test).
- A client test covers: the icon renders on a live console tab and is absent on the
  Overview tab.
- `./mvnw -B test` and `./.t-workflow/scripts/consistency-check.sh` pass.

## Scope
`client/src/app/components/console-tabs/`, a new engine controller/service alongside
`engine/src/main/java/dev/locklane/engine/persistence/ConsolesController.java`

## Explicitly not
- Tab drag-to-reorder — unrelated to this action, not touched here.
- "Select/highlight the file" behavior on Windows/Linux (Explorer supports `/select,`,
  Linux has no equivalent via `xdg-open`) — v1 just opens the containing folder
  everywhere.
- Any behavior when the client and engine are on different machines (remote locklane) —
  out of scope; the button's local-only assumption is not validated against that case
  here.

## Decisions made along the way
- New `FileManagerLauncher` service (engine) resolves a console id's working directory
  via `SessionRegistry.lastKnownWorkingDirectory`, the same lookup PTY spawning already
  uses — so the endpoint takes only a console id, never a path. The reveal endpoint
  itself lives on the existing `ConsolesController`
  (`POST /api/projects/{projectId}/consoles/{id}/reveal-in-file-manager`), reusing its
  existing `IssueWorktreeService.allWorktreeIds` ownership check rather than adding a
  new authorization path, since that check already covers both issue-worktree and
  project consoles.
- The per-OS command choice (`open`/`explorer.exe`/`xdg-open`) is a small static
  function taking `os.name` as a parameter, tested directly for all three OS strings —
  keeps the test deterministic across whatever OS actually runs CI.

## Deviations / notes
- Hit a real Spring wiring bug during implementation, not a deviation from scope: with
  `FileManagerLauncher` carrying two constructors (the public one Spring should use,
  plus a package-private one only tests use to inject a fake process runner), Spring
  could not disambiguate and failed the whole `ApplicationContext` at `./mvnw test`
  time (`No default constructor found`). Fixed by adding `@Autowired` to the public
  constructor, mirroring the existing pattern in `SessionRegistry`. No scope change —
  fixed in place before checks were declared passing.
