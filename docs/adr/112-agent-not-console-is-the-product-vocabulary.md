# ADR-112: "Agent", not "console", is the product's word for a running coding agent

**Status:** Accepted · 2026-09-07
**Deciders:** project owner *(solo phase; ratified by the human's confirmation at
`/t-ship`'s gate on task #765, under initiative #764)*

## Context

The app called the tab that runs an AI coding agent a "console", and separately offered
"shells". In everyday language those are the same thing: "console" says how the thing
is displayed, not what is inside it, so a person could not tell "Open a new console"
from "Open a shell console" by the words alone. The same noun leaked into the README,
the marketing site, and `CONSTITUTION.md` §4, and the UI named a resumable past agent
conversation "past sessions" in one place and "past conversations" in another.

Meanwhile "agent" already means two things in the code: the installed CLI program the
engine detects (`InstalledAgent*`, `AgentInfo`, the "Default agent" setting) and, in
plain speech, the running instance of one. A vocabulary has to say which is which where
a sentence needs both.

Initiative #764 settles one vocabulary and applies it in two tasks: this one (#765) for
every surface a person reads, and #766 for code identifiers, file names, tests and
comments. The vocabulary is ratified here so #766 renames against a decided glossary
rather than a convention it has to infer from the UI.

## Decision

### D1. The glossary

Wherever a person reads the product — the app's own screens, tooltips, aria-labels,
dialogs, empty states, the README, the marketing site, and `CONSTITUTION.md` §4 —
these five terms are used, and "console" is not:

- **agent** — a running agent CLI (claude, gemini, …) in a worktree, attached through
  a live terminal; opened under an issue or from the project page. The tab noun.
  Replaces "console" and "project console".
- **shell** — a plain shell at a worktree or at the main checkout. Lives in the Shells
  window. Unchanged.
- **conversation** — a past agent conversation that a new agent can resume. Replaces
  the UI's "past sessions".
- **session** — the engine's own word for any persistent PTY, agent or shell. Never
  shown to a user.
- **agent CLI** — the program an agent runs (claude, gemini, …), used only where a
  sentence must tell the program from a running agent (the settings dialog's "Which
  agent CLI a new agent launches with by default", the tab strip's "agent CLI for the
  new agent" picker). The "Default agent" setting keeps its name.

In code, where "agent" is already taken by the installed-CLI concept, the running
instance takes the compound **agent session** (`ProjectAgentSessionService`,
`AgentSessionTab`, …) — the naming rule #766 applies; this ADR fixes the vocabulary,
not the identifiers.

### D2. Compatibility surfaces never follow the vocabulary

On-the-wire and persisted names are compatibility surfaces, not vocabulary, and the
glossary never forces them to change: session id shapes (`<projectId>-console[-<hex>]`
and the issue-worktree shapes), the database schema, REST paths
(`/api/projects/{id}/console…`, `/api/projects/{id}/consoles…`), WebSocket event names
(`consolesChanged`, `consoleAttention`), route paths (`/projects/:id/console`,
`/shells`), localStorage keys (`locklane.lastConsole`, `locklane.activeConsoleByIssue`),
and the Shells window's `window.open` name. A person never reads them; renaming them
would cost a migration and buy nothing visible. Where such a name survives in the code
it is marked as a compatibility surface kept under this ADR (#766).

### D3. History keeps its wording

Pre-existing ADRs (append-only, `CONSTITUTION.md` §2.1), `CHANGELOG.md` entries, and
the task records under `docs/tasks/` keep the wording they were written with. The
glossary binds what is written from now on, not what was.

## Rationale

- One noun per concept, chosen by what is inside the tab rather than how it is shown:
  an agent runs an agent CLI, a shell runs a shell. "Console" described neither.
- "Conversation" is what a person resumes; "session" is what the engine persists. The
  UI already said "past conversations" in its newer surface (#752); the older "past
  sessions" heading was the inconsistency, not the rule.
- Keeping "session" out of user-visible text keeps the engine's abstraction (one word
  for agent and shell alike) from leaking into copy where it reads as a third kind of
  tab.
- Splitting the vocabulary (this task) from the identifier rename (#766) lets the
  user-facing change ship on its own and gives the code rename a ratified target.
- Excluding wire and persisted names is what makes the rename free: an existing
  install upgrades with no migration, and a browser tab left open across the upgrade
  keeps working.

## Alternatives considered

- **Keep "console" and rename "shell" instead** — rejected: "shell" is accurate and
  only read badly next to "console"; the confusing word was the display-oriented one.
- **"Terminal" for the tab noun** — rejected: every tab, agent or shell, is a
  terminal; the word says how it is displayed, exactly the fault "console" had.
- **"Session" as the user-visible noun** — rejected: it is the engine's word for
  agent and shell alike, so it cannot distinguish the two things a person opens.
- **Rename the wire and persisted names too, for consistency** — rejected: it would
  cost a Flyway migration, a route redirect and a localStorage migration for names no
  person reads (#764 Non-goals).
- **Rewrite history (ADRs, changelog, records) to the new words** — rejected: ADRs
  are append-only and records are contracts for cold sessions; their wording is part
  of what they recorded.

## Consequences / revisit triggers

- Every new user-visible string, doc page and site paragraph uses the glossary in D1;
  a cold review treats a fresh "console" on such a surface as a defect.
- #766 renames client and engine identifiers to the agent-session rule, marking each
  surviving D2 name as a compatibility surface under this ADR.
- The identifier hits left in templates by this task — component selectors such as
  `<app-console-tabs>`, CSS classes, element ids, and template bindings — are #766's
  to remove; until it lands, a grep for "console" over the templates finds identifiers
  only, never text a person reads.
- Revisit if a third kind of tab appears that is neither an agent nor a shell, if the
  engine ever exposes "session" to a user deliberately, or if a compatibility surface
  in D2 has to change for a reason of its own (a schema migration, say) — the
  vocabulary then no longer argues against renaming it in the same change.
