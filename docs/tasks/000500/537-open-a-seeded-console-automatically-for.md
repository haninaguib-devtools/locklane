# 537 — Open a seeded console automatically for a project created from a template
Issue: #537 · Part of: #535

## Asked
Make creating a project from a template end in a working console with no further
clicks. After #536 the operator picks a template, presses create, the dialog closes and
the sidenav refreshes; they would then have to find the project, open a console and
type the instruction themselves. After this task, pressing create on the **create** tab
navigates straight to the new project's console page while it is still cloning, and
the moment the project turns READY the page opens a console tab itself, launching the
operator's default agent with a first prompt already submitted: read
`PROJECT_TEMPLATE.md` and build the project it describes. The operator's presence is
only needed for the agent's permission prompts. This happens exactly once per project
and survives a reload or a later visit, because the rule is stateless: a READY project
with a `template` set whose seeded console has not yet been launched gets one;
launching it is what turns the rule off.

The first prompt is composed by the engine, never by the browser, and differs by
bootstrap kind, detected from the checkout rather than stored: a checkout carrying a
`.t-workflow/` directory was bootstrapped with t-workflow, and its preface tells the
agent to open a task with `/t-open` describing the scaffold and drive it through the
pipeline, since that repository's rules forbid changing the tree outside a task. Any
other checkout gets a preface telling the agent to build the scaffold in the current
worktree and push it to `main` when done, because a console runs in a detached worktree
and a scaffold left there would never be seen. In both cases the preface is followed by
the instruction to read `PROJECT_TEMPLATE.md` and follow it; the template body itself
is not placed on the command line.

## Done when
- The WebSocket attach (`/ws/sessions/{sessionId}?cmd=<claude|codex|opencode>&seed=template`)
  launches the agent with the engine-composed first prompt: `claude "<prompt>"`,
  `codex "<prompt>"`, and the equivalent for `opencode` (its TUI initial-prompt flag,
  confirmed against the installed version). `seed` is ignored for `cmd=shell`, for any
  session id that is not a project console session, and for a project with no
  `template`. Covered by `TerminalWebSocketHandler` unit tests of the launch-command
  resolution for all three agents and both prefaces.
- On that seeded launch the engine records it on the project (a nullable
  `template_seeded_at` timestamp column, added by a new Java migration following
  `V12__AddAccentColorToProjects`), returned as `templateSeededAt: string | null` on
  the project JSON. A second attach with `seed=template` on an already-seeded project
  launches the agent without a prompt. Covered by an engine test.
- After a successful create on the create tab, the client navigates to
  `/projects/<id>/console` immediately, while the project still shows CLONING; the
  sidenav's existing three-second cloning poll is what flips it. Creating with no
  template also navigates there (same route, no seeding); the import tab keeps today's
  behaviour (dialog closes, sidenav refreshes). Covered by a component spec.
- The project console page, whenever it renders a project that is READY, has
  `template` set and `templateSeededAt` null, mints a new console session and attaches
  it with the default agent and `seed=template`, without a click; it does this at most
  once per page instance and never for a CLONING or FAILED project. A project whose
  creation failed and is later retried to READY triggers the same. Covered by a
  component spec with the project transitioning CLONING → READY under the spec.
- After that first launch, reloading the page or returning to the project later does
  not open another seeded console. Covered by the spec above with `templateSeededAt`
  set.
- The seeded console tab is a normal console in every other respect: closable,
  resumable, listed by the tab strip, same worktree lifecycle.
- `./mvnw -B test` passes and the client spec suite passes.

## Explicitly not
- Running the agent headlessly or unattended; the seeded launch is an ordinary
  interactive console.
- Choosing a different agent for the seeded launch than the default agent the settings
  dialog already picks.
- Any change to the import path.

## Decisions made along the way
- The seeded first prompt lives in `ProjectConsoleService` as two constants
  (`PLAIN_SEED_PROMPT`, `T_WORKFLOW_SEED_PROMPT`); `templateSeedPrompt(sessionId,
  workingDirectory)` picks one by whether the console's own worktree carries a
  `.t-workflow/` directory, and returns empty unless the session id is a project
  console's, the project has a `template`, and `templateSeededAt` is still null.
  `markTemplateSeeded` applies the same test before writing, so a stray `seed`
  parameter can never stamp an unrelated project (agent, 2026-09-01).
- `TerminalWebSocketHandler.resolveLaunch` returns a small `Launch(command, seeded)`
  record; the attach records the launch only when `seeded` is true, and seeding
  requires no live process for the session, an agent `cmd`, no `resume`, and
  `seed=template` exactly — so a reattach, a resumed conversation, and a shell are
  never seeded. The existing three-argument resolution is untouched and still used
  for every non-seeded case (agent, 2026-09-01).
- Initial-prompt shapes confirmed on this host: `claude <prompt>` and `codex <prompt>`
  positional; `opencode --prompt <prompt>` (opencode 1.18.25 lists `--prompt` for its
  TUI). The prompt is one argv element, never shell text (agent, 2026-09-01).
- The console page reads `/api/projects` before its console list (one extra request
  per page load), because no shared stream of project status exists: the sidenav's
  three-second cloning poll updates only the sidenav's own list, and
  `CurrentProjectService` fetches once. While the project is CLONING the page shows a
  waiting line and re-reads on the same three-second cadence itself; FAILED shows a
  hint pointing at the project page's retry; a project absent from the list, or a
  failed read, falls through to the pre-#537 flow so nothing older depends on the
  lookup (agent, 2026-09-01).
- Navigation after a successful create lives in the popup component itself, which
  knows which tab submitted; `created` is still emitted afterwards so the host closes
  the dialog and refreshes the sidenav as before. Import never navigates (agent,
  2026-09-01).
- The client `Project` model declares `templateSeededAt` optional (`?: string | null`)
  rather than required as `template` is, so the spec fixtures outside this task's
  Scope line keep compiling untouched; the engine always sends the field (agent,
  2026-09-01).

## Deviations / notes
- **Sibling blocker discharged by the driving session.** #537 is blocked by #536,
  which is merged into this task's base branch `wip/535-integration` (child PR #547,
  commit 3959b25) but stays open until the initiative's aggregate PR merges, as
  ADR-004 designs it. `/t-work`'s blocker gate reads that as unsatisfied; the
  `/t-drive 535` session directed the same reading the driven run of #462 applied:
  the sibling's outcome is "merged", its work is in this branch's base, so the
  blocker counts as discharged for a driven child (2026-09-01).
