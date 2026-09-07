# 765 — Rename console to agent in the UI, docs and constitution
Issue: #765 · Part of: #764

## Asked
Everywhere a person reads the product — the app's own screens, the README, the
marketing site, and the constitution's stack section — a tab that runs an AI coding
agent is called an **agent**, not a "console". A plain shell stays a **shell**. A
resumable past agent conversation is a **conversation** (today the UI says "past
sessions" in one place and "past conversations" in another). The program an agent
runs (claude, gemini, …) is the **agent CLI** where a sentence must tell it from a
running agent, and the "Default agent" setting keeps its name. This task writes that
glossary down as an ADR, so the sibling code-identifier task can rename against a
ratified vocabulary, and applies it to every human-readable surface. The vocabulary
itself is the initiative's (#764) Goal.

## Done when
- `docs/adr/112-<slug>.md` exists with status Accepted, defining the five terms
  (agent, shell, conversation, session, agent CLI) and two rules: on-the-wire and
  persisted names — session id shapes, database schema, REST paths, WebSocket event
  names, route paths, localStorage keys, the Shells window's `window.open` name — are
  compatibility surfaces the vocabulary never forces to change; and pre-existing
  ADRs, CHANGELOG.md entries, and task records keep their wording.
- CONSTITUTION.md §4 points 2, 4 and 5 say "agent" where they say "console" today
  (an agent-created per-issue worktree; a project-agent worktree; a worktree/agent
  session), with the ADR referenced there per §2.3. No other section of the
  constitution changes.
- `grep -rIn -i console client/src --include='*.html'` returns nothing.
- Every user-visible string in `client/src/app` TypeScript uses the new words: tab
  labels (`console`, `console 2` become `agent`, `agent 2` in `console-labels.ts`'s
  project-tab rule; the issue-tab rule's `main`/`wtree` labels are unchanged),
  tooltips, aria-labels, dialog titles, error texts ("could not start a console — try
  again"), empty states ("no past conversations in this project's consoles yet"), the
  header widget's "Open consoles" and "Choose a console to jump back into", and the
  settings dialog's "Which agent new consoles launch with by default."
  `grep -rIn -i console client/src/app --include='*.ts' | grep -v -E
  'console\.(log|error|warn|info|debug)'` shows no hit inside a string literal that
  reaches a person. Identifiers, comments, id-shape regexes, REST paths, event names
  and localStorage keys may still say console: they belong to the sibling task or
  stay permanently.
- The project page's "past sessions" heading says "past conversations".
- README.md's paragraph on running `locklane` from a terminal outside the app says
  agent tab, not console tab; `site/index.html`'s "Issue consoles run in separate git
  worktrees" and "Open a console" say agent; `docs/architecture/logging.md`'s "the
  console shows a failed state" says "the app shows".
- Client specs asserting the changed strings are updated; `./mvnw -B test` passes;
  `./.t-workflow/scripts/consistency-check.sh` passes.
- Human check: from an issue, open an agent and a shell; open the Shells window;
  rename a tab; close each with its confirmation dialog; open the header widget and
  the settings dialog. Every label, tooltip, empty state and dialog reads
  consistently with the glossary. A person judges this.

## Explicitly not
- Identifiers, file names, class names, javadoc and comments in client and engine
  code — split to #766.
- Route paths (`/projects/:id/console`, `/shells`), REST paths, session id shapes,
  the `consolesChanged` event, localStorage keys: unchanged permanently, per the
  initiative.
- Existing ADRs, CHANGELOG.md, and docs/tasks/ records: historical, untouched.
- The "Default agent" setting's name and the installed-CLI code.

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- Wording where the issue left a choice (Claude, 2026-09-07): "Open a shell console
  for this project" became "Open a shell for this project" (a shell is a shell); the
  Shells window's worktree row shows `agent` where it showed `console`; the header
  widget reads `agent` / `agents (n)`, its heading "Open agents", its aria-label
  "Open agents, n agent(s)" (the old one said "session(s)", a word the glossary keeps
  out of user-visible text); the issue tab strip's open button reads "Agent"; the
  close dialog says "Close this agent? It will be terminated and cannot be
  reattached." for the same reason; the settings dialog says "Which agent CLI a new
  agent launches with by default." and the tab strip's picker "agent CLI for the new
  agent" — the two places a sentence must tell the program from the running agent;
  the account-deletion dialog says "any open worktrees or agents on them"; README
  says "outside Locklane itself … an agent tab or a shell tab", since the refusal
  applies to both kinds of tab.
- The issue page's "past sessions" heading (`overview-tab`) changed alongside the
  project page's: the glossary replaces "past sessions" everywhere, and the component
  is in Scope. (Claude, 2026-09-07)
- `CONSTITUTION.md` §4 gets a new point 7 carrying the glossary's one-line rule with
  its ADR-112 pointer (§2.3), inside the same `<!-- local -->` slot as points 2, 4
  and 5; nothing outside §4 changed. (Claude, 2026-09-07)
- Spec `it(...)` descriptions changed only where they quote a changed string verbatim
  (`reads "Open agent"`, `shows "agent" instead of an issue number`); descriptions
  that merely say "console" in prose are test names, #766's. (Claude, 2026-09-07)

## Deviations / notes
- The issue's literal HTML criterion (`grep -rIn -i console client/src
  --include='*.html'` returns nothing) cannot hold before #766: every remaining hit
  is an identifier — component selectors (`<app-console-tabs>`,
  `<app-console-indicator>`, `<app-project-console>`), template bindings
  (`closeConsole($event)`, `@for (console of consoles`, `startingConsole`,
  `consoleButtonLabel`, `hasOpenConsole…`), CSS classes (`console-dot`,
  `project-consoles`, `new-console`, `console-button`) and element ids
  (`console-picker-title`, `console-row-<n>`) — which this task's Non-goals assign to
  #766. The plan pinned the check to text a person reads; that check is clean. The
  plan's command additionally needed `grep -v "'console-row-'"` for the two id
  fragments inside `[id]`/`aria-activedescendant` bindings. (Claude, 2026-09-07)
- The plan's TypeScript check, run literally, also matched comment lines containing
  an apostrophe ("project's console page"), the `.console-button` CSS selector in
  specs, the route-path comparison `segment.path === 'console'`, and fixture issue
  titles in `tree-filter.spec.ts` (`task(1, 'Closed but has a console', …)`) — none
  of them text the product shows. The command as run adds `grep -v` filters for
  comment lines, `\.console-`, `=== 'console'`, and `(task|initiative)(<n>, '`
  fixtures; with those it prints nothing. No product string is exempted by them.
  (Claude, 2026-09-07)
- Engine-authored error text ("This project has an open worktree or console — close
  it before deleting the project.", `WorktreeCreationService`'s and
  `WorktreeCleanupSweeper`'s "a console session is still attached …") still says
  console: the engine is outside this task's Scope, and #766's own done-when grep
  over `engine/src` catches those strings. The client specs mocking that engine text
  keep it verbatim. (Claude, 2026-09-07)
- Human check (the issue's walk-through) stays the human's: it needs a running server
  with a real agent CLI, and is a judgment of consistency rather than a fact a grep
  settles. Every string it would read is covered by the greps and the updated specs.
