# 766 — Rename console identifiers to agent session in client and engine code
Issue: #766 · Part of: #764

## Asked
With the vocabulary ratified by #765 (ADR-112), the code should read the way the product
does: a class, file, variable, test or comment about a running agent's tab says agent,
not console. Because "agent" in code already means the installed CLI program
(`InstalledAgent*`, `AgentInfo`, `AgentStore`, the `Agent` type), the running-instance
concept takes the compound **agent session** in code — the natural counterpart of the
existing `ShellSessionService` — so `ProjectConsoleService` becomes
`ProjectAgentSessionService`, `ConsolesService` becomes `AgentSessionsService`,
`console-tabs` becomes `agent-session-tabs`, `ConsoleInfo`/`ConsoleTab` become
`AgentSessionInfo`/`AgentSessionTab`, and so on by the same rule. Nothing persisted or on
the wire moves: a database made by today's build and a browser tab left open across the
upgrade both keep working.

## Done when
- No class, interface, file, directory, function, variable, test name, javadoc or comment
  in `client/src` or `engine/src` uses "console" for a running agent session.
  `grep -rIiw console client/src engine/src` lists only: (a) the browser console API;
  (b) the persisted session id shapes — the `<projectId>-console[-<hex>]` regexes and
  the minting that produces them; (c) REST path strings under
  `/api/projects/{id}/consoles`; (d) the `consolesChanged` WebSocket event name;
  (e) route path strings (`projects/:projectId/console`); (f) localStorage keys; (g) the
  Shells window's `window.open` name. Each hit in (b)–(g) sits on or next to a comment
  naming it a compatibility surface kept under ADR-112.
- The installed-CLI names (`engine/.../agent/InstalledAgent*`, `AgentInfo`, client
  `AgentStore`, the `Agent` type) are unchanged.
- No Flyway migration is added: `engine/src/main/resources/db/migration/` is untouched,
  and no table or column is renamed.
- The client's REST calls and the engine's controller mappings are identical before and
  after: `grep -rho "'/api/[^']*'" client/src | sort -u` and
  `grep -rhoE '@(Get|Post|Put|Delete|Request)Mapping\([^)]*\)' engine/src | sort -u`
  produce the same output on the task branch as on the base.
- `./mvnw -B test` passes; `./.t-workflow/scripts/consistency-check.sh` passes.
- Rename only: the diff is file renames plus identifier, javadoc and comment edits, with
  no behaviour change. The cold review checks this.

## Explicitly not
- User-visible strings, docs, the ADR and the constitution — #765 (done on the base).
- The wire and persisted names in (b)–(g) above: unchanged permanently, per the
  initiative (#764).
- Splitting or restructuring the session families (project agent, issue worktree, main
  checkout, resume, shell): rename only, no behaviour change.

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- **Mapping rules** (agent, 2026-09-08), applied mechanically, longest names first, after
  shielding every compatibility surface with a placeholder:
  - CamelCase: `Console` → `AgentSession`, `Consoles` → `AgentSessions` (so
    `ProjectConsoleService` → `ProjectAgentSessionService`, `ConsolesController` →
    `AgentSessionsController`, `ConsoleTabsComponent` → `AgentSessionTabsComponent`,
    `ConsoleInfo`/`ConsoleTab`/`ConsoleEntry`/`ConsoleGroup` → `AgentSession…`,
    `OpenConsole`/`OpenProjectConsole`/`OpenConsoleView` → `OpenAgentSession…`,
    `ConsoleResumeSessionRecord`/`Repository` → `AgentSessionResumeSessionRecord`/
    `Repository`, `ProjectConsoleWorktree` → `ProjectAgentSessionWorktree`,
    `ConsolesChangedEvent`/`isConsolesChangedEvent`/`broadcastConsolesChanged` →
    `AgentSessionsChanged…`, `ConsoleAttentionEvent` → `AgentSessionAttentionEvent`,
    every test method name likewise).
  - camelCase: `console…` → `agentSession…`, `consoles…` → `agentSessions…`
    (`consoleId` → `agentSessionId`, `consolesService` → `agentSessionsService`,
    `flushConsoles` → `flushAgentSessions`, the bare variables `console`/`consoles` →
    `agentSession`/`agentSessions`).
  - UPPER_SNAKE: `CONSOLE_SESSION_ID` → `AGENT_SESSION_ID`, `PROJECT_CONSOLE_PREFIXED` →
    `PROJECT_AGENT_SESSION_PREFIXED`.
  - kebab (files, directories, selectors, CSS classes, element ids): `console-tabs` →
    `agent-session-tabs`, `console-indicator` → `agent-session-indicator`,
    `console-labels` → `agent-session-labels`, `project-console` →
    `project-agent-session`, `consoles.service` → `agent-sessions.service`,
    `active-console-store`/`last-console-store` → `active-agent-session-store`/
    `last-agent-session-store`, `app-console-tabs` → `app-agent-session-tabs`,
    `.console-dot`/`.new-console`/`.project-consoles`/`.console-button`/
    `#console-picker-title`/`console-row-` → the `agent-session` forms.
  - English in comments, javadoc, log and error messages: `console` → `agent session`,
    `consoles` → `agent sessions`, with the article fixed (`a console` → `an agent
    session`).
- **One deliberate exception to the mechanical rule** (agent, 2026-09-08): the
  compound `ConsoleSession` collapses to `AgentSession` instead of becoming
  `AgentSessionSession` — `ConsoleSessionTitles` → `AgentSessionTitles`,
  `ProjectConsoleService.ConsoleSession` → `ProjectAgentSessionService.AgentSession`,
  `ProjectConsoleSession` (client) → `ProjectAgentSession`,
  `isProjectConsoleSessionId` → `isProjectAgentSessionId`, `CONSOLE_SESSION_ID` →
  `AGENT_SESSION_ID`, and the prose "console session" → "agent session". Where the
  old text said "console" for a PTY of *either* kind (agent or shell — the shutdown
  log line "console processes still alive", `ProcessTrees`' javadoc, "Shell-kind console
  sessions", "a shell console") it now says "session"/"shell session", the engine's own
  word per ADR-112 D1, rather than the wrong "agent session".
- **Comments that quoted old UI labels** were aligned with the label as it reads since
  #765 rather than mechanically renamed: `"Open console"` → `"Open agent"`, the issue
  page's `"Console"` button → `"Agent"`, `("console", "console 2")` → `("agent",
  "agent 2")`; test fixtures carrying `label: 'console'` became `label: 'agent'`, and
  two spec titles that quoted `"console"`/`"consoles (N)"` now quote what the widget
  actually renders (`"agent"`/`"agents (N)"`).
- **Engine strings a person can read but #765 did not touch** (agent, 2026-09-08): the
  409/refusal texts "a console session is still attached to this worktree…", "This
  project has an open worktree or console…", "…and a console session is still attached
  to it… close that console first", "Console worktree creation failed" (log) and the
  IDE/file-manager "for console {}" log lines now say "agent session" by the same
  mechanical rule — the Done-when grep leaves no other option. ADR-112 D1 says "session"
  is never shown to a user, so a follow-up may want to polish these five messages to
  "agent"; flagged in the PR body, not done here (rename only).
- **Arbitrary test data** that merely happened to say console — the `"/work/console"`
  fixture path, the `"console/*"` branch-pattern example in `GitTestRepos`, the
  `console-token-user` fixture usernames — was renamed with everything else; every id
  and directory fixture of the persisted shape (`"1-console-a1b2c3d4"`,
  `/repo-console-aaaa0001`) was kept byte-identical.
- **Compatibility surfaces kept, each with an ADR-112 note on or beside it**: the
  `^(\d+)-console(-.+)?$` regexes and the `"-console-"` minting (engine service,
  sweeper, creation service, client id helpers); the `/api/projects/{id}/consoles…` and
  `/api/projects/{id}/console…` REST paths (controllers' `@RequestMapping`, the client
  services, `SecurityConfig` matchers, the IDE proxy path family); the `consolesChanged`
  and `consoleAttention` WebSocket event strings (identifiers around them renamed); the
  `projects/:projectId/console` route and every `'console'` route segment the client
  navigates with; the `locklane.lastConsole`/`locklane.activeConsoleByIssue`
  localStorage keys; the `console_resume_sessions` table name in the repository's SQL.
  The Shells window's `window.open` name is `'locklane-shells'` and never said console.
  Test files carrying id/path fixtures got one file-level note each rather than one per
  fixture.
- Two javadoc/comment references to the pre-#340 `console/<suffix>` branch shape were
  reworded to "per-session branch (retired by #340)" so no un-annotated `console`
  survives the Done-when grep.

## Deviations / notes
- Out of scope, reported for the driver, not edited: three comment-only hits outside the
  Scope line still say console — `client/src/styles.css:60` ("right of the console"),
  `engine/src/main/resources/application.yml` (three comments: "console-terminal uploads",
  "a console terminal", "console-created worktree"), and the comments in
  `engine/src/main/resources/db/migration/V8__create_console_resume_sessions.sql`
  (the migration directory is untouched by design). `client/src/main.ts`'s only hit is
  `console.error` (category (a)).
- The bulk edit was a shielded perl pass (`rename.pl`) followed by a hand sweep of every
  surviving hit and a round-trip check (reverse-mapping the new tree and diffing it against
  the base) to isolate every non-mechanical edit for review.
