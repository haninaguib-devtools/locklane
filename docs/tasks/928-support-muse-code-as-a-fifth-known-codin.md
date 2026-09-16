# 928 — Support Muse Code as a fifth known coding agent
Issue: #928

## Asked
Locklane treats four terminal AI coding agents — Claude Code (`claude`), Codex (`codex`), OpenCode (`opencode`) and OMP (`omp`) — as first-class: it detects whether each is on `PATH` at boot, offers the installed ones in the agent picker (the server is the only source of ids and labels, #695), captures a session's resume id from its output, composes the plain / seeded-prompt / resume launch commands, titles past conversations from the CLI's own storage, and, where one exists, shows subscription usage in the usage widget. Add Muse Code — Meta's terminal coding agent, installed as `muse` (the bash launcher at `~/.local/bin/muse` downloads and execs `muse-bin-<version>`; `muse --version` prints `Muse Code 1.3.0 (1.3.0-R3057.1)` at the time of writing) — as a fifth member of that same set, so it shows up everywhere the other four do.

What was verified against the installed Muse Code 1.3.0, so the task does not have to rediscover it:

- Launch: `muse` with no args opens the interactive TUI; `muse <prompt>` starts a session with that prompt (positional, like `claude`/`codex`/`omp`).
- Resume: `muse resume <session-uuid>` (also `muse resume --last`). Session ids are UUIDv7 (e.g. `01a0a7b6-912f-7f61-80a7-571b55b8a906`), so the existing `UUID` pattern in `ResumeIdScanner` fits; the resume-command pattern is `muse resume <uuid>` — the Codex shape, not the `--resume` shape.
- Storage: `~/.local/share/muse/sessions/<YYYY>/<MM>/<DD>/<session-uuid>/session.jsonl`, plus an SQLite index `~/.local/share/muse/session-index.db` whose `sessions` table has `session_id`, `workspace_root`, `title`, `first_user_prompt`, `created_at_us`, `updated_at_us`. That index is the natural source for `AgentSessionTitles` (one read for the whole batch, keyed by resume id, like Codex's index file). `~/.local/share/muse/tui-history.jsonl` also logs `{"project": <cwd>, "session": <uuid>}` per TUI launch.
- Config: `~/.config/muse/settings.json` (provider `meta`, model, reasoning effort), `auth.json` (Meta OAuth token — never read by Locklane), `trust.json` (per-workspace trust). The `--trust-workspace` flag loads a workspace's `AGENTS.md` rules for one run; an untrusted workspace skips them with a warning.
- Usage: the model is served by Meta's Model API (`https://api.meta.ai/v1`) on a pay-as-you-go or subscription account. No usage/quota endpoint was found in the binary's strings, so there may be nothing Locklane can query.

- Hooks: Muse Code 1.3.0 fires `session_start`, `user_prompt_submit`, `pre_tool_use`, `permission_request`, `post_tool_use`, `stop`, `notification`, `session_end`, `subagent_stop`, `stop_failure`. Each hook is a `command` argv that receives a JSON payload (an example bundled in the binary maps `stop`, `permission_request` and `notification` to `hooks/stop.py`, `hooks/permission_request.py`, `hooks/notification.py`). Hooks are declared in a plugin bundle's `hooks/hooks.json` (with a manifest), in a workspace `.muse/hooks.json`, or through the launch-time `--agents <JSON>` agent-definition overlay whose `capabilities.hooks` list is the closest analogue to Codex's `notify` override and OMP's `--hook=<file>`.

This task also gives Muse Code the ADR-113 bell: Locklane ships one bell script (reusing the `LOCKLANE_TTY` contract of `CodexBellHookScript`), written under `${locklane.data-dir}/hooks/` at engine start, and attaches it to every `muse` launch — plain, seeded-prompt and resume alike — so a Locklane agent tab shows the amber "waiting" dot the instant a Muse turn ends or Muse asks for approval or input, the same as Claude Code (#855), Codex (#856), OMP (#857) and OpenCode (#858). The user's own Muse configuration is never touched.

## Done when
- `InstalledAgentsStore.KNOWN_AGENTS` (`engine/src/main/java/dev/locklane/engine/agent/InstalledAgentsStore.java`) includes `new AgentInfo("muse", "Muse Code")`, detected the same way as the other four (PATH scan at boot via `InstalledAgentDetector`), so `muse` appears in the picker and settings when installed and nowhere when not. No client change is needed for the list itself (#695); if any client enumeration of agent ids still exists, it is updated too.
- `ResumeIdScanner` (`engine/src/main/java/dev/locklane/engine/pty/ResumeIdScanner.java`) has a `MUSE` tool hint (basename `muse`) and matches `muse resume <uuid>`; the labeled `session id: <uuid>` fallback applies with the `muse` hint. The record states how a running Muse TUI actually exposes its session id on screen (it was not confirmed that it prints one) and, if it does not, which alternative the task chose and why — e.g. reading the newest `session-index.db` row whose `workspace_root` is the session's directory, or a `session_start` hook (Muse Code fires `session_start`, `user_prompt_submit`, `pre_tool_use`, `permission_request`, `post_tool_use`, `stop`, `notification`, `session_end` hooks) that echoes the id. A unit test covers the chosen path.
- `TerminalWebSocketHandler` (`engine/src/main/java/dev/locklane/engine/ws/TerminalWebSocketHandler.java`) accepts `cmd=muse`, composes `muse` (plain), `muse <prompt>` (seeded) and `muse resume <id>` (resume), treats `muse` as an agent for the quiet-output fallback and reattach-resume logic exactly like the other four; unit tests assert each argv.
- `AgentSessionResumeSessionRecord`, `AgentSessionTitles` (`engine/src/main/java/dev/locklane/engine/persistence/`) and their Javadoc name `muse` alongside the others; `AgentSessionTitles` returns Muse titles from `session-index.db` (read once per batch, bounded like the others, missing DB reads as "no titles"), with a test on a fixture database.
- The usage widget question is explicitly resolved, not silently skipped: either Muse Code has a locally-queryable usage source comparable to `ClaudeUsageProvider`/`CodexUsageProvider` (`engine/src/main/java/dev/locklane/engine/usage/`) and it is wired the same way, or the task record states why not (no discoverable balance/quota endpoint for Meta's Model API) and the widget is left untouched for `muse`, as was done for `omp` in #681.
- After engine start, the Muse bell hook file exists under `${locklane.data-dir}/hooks/`, overwritten on every start (the "always correct, not merely present" idempotence of #856); an engine test covers creation and overwrite.
- Every `muse` argv `TerminalWebSocketHandler` composes carries the hook attachment (the exact flag or overlay is verified against the installed `muse` and named in the record); a unit test asserts it and that other commands are untouched.
- The hook fires on `stop` (a turn ended for the user), `permission_request` (approval wanted) and `notification` (Muse needs attention), guarded so a stop that Muse is about to auto-continue does not ring; the record names the events used and the payload fields read.
- In a real Locklane agent tab, a Muse turn ending makes the amber dot appear at that instant, and a pending approval rings too; a human confirms this before or shortly after shipping since `muse` is not installed in CI.
- Product wording follows ADR-112: the label a person sees is "Muse Code"; `muse` is the on-the-wire id.
- `scripts/check.sh` passes (engine and client test suites).

## Explicitly not
- Touching `~/.config/muse/` or any workspace's `.muse/` directory.
- Passing `--trust-workspace`, `--yolo`, a model, or any other Muse Code policy flag on Locklane's launches; the user's own Muse configuration is left alone.
- Reading `~/.config/muse/auth.json`.
- Any Muse-only UI surface beyond parity with the existing four agents.
- Changing the shape of `InstalledAgentDetector`, `ResumeIdScanner`, `AgentSessionTitles` or the `UsageProvider` interface themselves — `muse` slots into the existing pattern.

## Decisions made along the way
- **Hook attachment: `TBH_MANAGED_HOOKS_PATH`, carried through `env`.** The `--agents <JSON>`
  overlay the issue named turned out not to be a hooks surface: Muse Code 1.3.0 accepts any
  overlay shape without error and never runs a hook declared in one (tested with `{"capabilities":
  {"hooks":[...]}}` and three sibling shapes under the offline `--provider echo`; none fired).
  Reading the binary's strings instead surfaced `TBH_MANAGED_HOOKS_PATH`, the environment
  variable Muse Code reads a "managed hooks" file from (alongside the `managed_hooks_path`
  settings key), and that was confirmed live: a hooks file named there loads on every launch,
  trusted workspace or not, and fires on the events below. Nothing under `~/.config/muse/` or a
  workspace's `.muse/` is touched — a managed file loads alongside whatever the user declares.
  Since Muse Code has no launch flag for it, `TerminalWebSocketHandler` composes every `muse`
  argv as `env TBH_MANAGED_HOOKS_PATH=<data-dir>/hooks/muse-hooks.json muse ...` so the
  attachment stays visible on the launch itself (`withMuseBellHook`), the same way every other
  agent's argv carries its own.
- **Hooks file format.** Muse Code's hooks file is Claude Code-shaped:
  `{"hooks":{"<Event>":[{"hooks":[{"type":"command","command":"<path>"}]}]}}` with PascalCase
  events (`SessionStart`, `Stop`, `PermissionRequest`, `Notification`, ...). Discovered by
  feeding it a wrong file and reading the TUI's own validation line (`hooks.json:
  UnsupportedHandler: D66: unknown handler field \`timeoutMs\` ...`), so only `type` and
  `command` are declared. The bundled plugin example the issue cites (`"event": "Stop", "command":
  [...]`) is the plugin-bundle manifest shape, not the hooks-file shape.
- **Events and payload fields.** The one script (`<data-dir>/hooks/muse-bell.sh`,
  `MuseBellHook`) reads the JSON payload Muse Code pipes on stdin and switches on
  `hook_event_name`: `Stop` rings unless `stop_hook_active` is `true` (Muse Code's flag for a
  stop it is about to continue itself — the same guard Claude Code's own `stop_hook_active`
  carries; `max_consecutive_stop_hook_continuations` in its settings names the mechanism);
  `PermissionRequest` and `Notification` ring unconditionally; `SessionStart` never rings.
  Payloads seen live: `{"hook_event_name":"SessionStart","source":"startup","session_id":...,
  "cwd":...,"transcript_path":null,"model":...,"permission_mode":...}` and
  `{"hook_event_name":"Stop","stop_hook_active":false,"last_assistant_message":...,
  "session_id":...,"turn_id":...,...}`. `PermissionRequest` and `Notification` could not be
  triggered under the offline echo provider (no tools run), so their firing is part of the
  human confirmation the issue already asks for; their names come from the binary's hook event
  list (`SessionStartPreToolUsePermissionRequestPostToolUse...Notification...`).
- **Bell goes to `/dev/tty`, not `LOCKLANE_TTY`.** Muse Code scrubs a hook's environment down
  to an allowlist (`TERM`, `SHELL`, `TMPDIR`, `USER`, `PATH`, `PWD`, `HOME`, `LOGNAME`) — verified
  by dumping `env` from inside a hook — so the `LOCKLANE_TTY` contract of #904 cannot reach it.
  Unlike Claude Code's and Codex's hooks, though, Muse Code's hook child keeps the launching
  terminal as its controlling terminal (`ps -o tty` inside the hook showed the session's pty,
  and a write to `/dev/tty` arrived on the pty's master side), so the script writes there
  directly — OMP's shape (#857), not Codex's. Deviation from the issue's "reusing the
  `LOCKLANE_TTY` contract" wording, forced by the tool.
- **Session id: a `SessionStart` hook, not the screen or the index.** A running Muse Code TUI
  prints no session id at plain startup (confirmed: the whole pty stream of an echo-provider
  session holds none), so the labeled `session id:` fallback never applies in practice. Of the
  issue's two alternatives, the newest `session-index.db` row for the working directory is racy
  (the row appears only after the first turn and two tabs in one directory are ambiguous), so
  the hook carries the id instead: on `SessionStart` the script writes
  `ESC P locklane;muse resume <session_id> ESC \` — a DCS string — to the terminal. xterm.js
  6.0.0 (the client's version, tested with `@xterm/headless`) swallows an unknown DCS whole, so
  nothing shows on screen; `ResumeIdScanner` strips only the two ESC sequences and reads the
  `muse resume <uuid>` left between them, which its new `MUSE_RESUME_COMMAND` pattern captures
  with the tool named. `ResumeIdScannerTest` pins that byte sequence so a future change to
  the scanner's stripping fails loudly rather than silently losing Muse ids. Verified end to
  end: with the engine-written files, `env TBH_MANAGED_HOOKS_PATH=... muse --provider echo`
  produced exactly one bare BEL after the turn and one DCS marker carrying the id.
- **Resume.** `muse resume <uuid>` (root options may sit on either side of `resume`).
  Verified the composed shape resumes the right session and rings once per turn. Muse Code does
  not fire `SessionStart` on a resume (only `"source":"startup"` was ever seen), and the TUI then
  prints `resumed session <uuid>` — not the `session id:` label — but nothing is lost: the id
  being resumed is already the one captured at startup.
- **Titles: `session-index.db`, one query per batch.** `AgentSessionTitles` opens the index
  read-only (`SQLiteConfig.setReadOnly`) through the `sqlite-jdbc` driver the engine already
  ships, selects `session_id, session_name, title FROM sessions WHERE session_id IN (...)` for the
  batch's own ids (sliced at 500 to stay under SQLite's bound-parameter cap), and prefers the
  user's `session_name` over the generated `title`. Missing file, blank title, or a file that is
  not an SQLite database all read as "no titles". Data dir is `$XDG_DATA_HOME/muse` or
  `~/.local/share/muse`, matching the binary's own `${XDG_DATA_HOME:-$HOME/.local/share}/muse`
  string.
- **Usage widget: left untouched for `muse`, as for `omp` (#681).** Muse Code talks to
  `https://api.meta.ai/v1`; the only `/v1/...` paths in the binary are `/v1/asr/duplex`,
  `/v1/logs` and `/v1/traces` (speech, logging, telemetry), and no string mentions usage,
  quota, balance, billing or credits. The subscription itself is managed at
  `accountscenter.meta.com/muse_code/`, a web page, not an API. With no locally-queryable
  usage source comparable to Claude's or Codex's, there is nothing to wire; `UsageProvider`
  and the widget are unchanged.
- **Client.** No agent-id enumeration exists in `client/src/app` (the only `'omp'` literals
  are sample data in spec files), so no client change — the picker and labels come from the
  server (#695).

## Deviations / notes
- The bell script writes to `/dev/tty` rather than `$LOCKLANE_TTY` (see Decisions): Muse
  Code's hook environment never carries the variable.
- The attachment is an environment variable through `env`, not a flag or the `--agents`
  overlay named in the issue (see Decisions): the overlay does not load hooks.
- `PermissionRequest`/`Notification` firing and the amber dot in a real Locklane tab remain
  for the human confirmation the issue's done-when already names; `muse` is not in CI, and
  the offline echo provider runs no tools.
- Out of scope, noticed in passing: `ResumeIdScanner.toolHintFor` sees the *wrapped* argv
  (`sh` for claude/codex since #904, now `env` for muse), so the labeled `session id:`
  fallback already never applies to those launches. Harmless today — every one of them
  names its tool in the captured command — but worth its own issue if a labeled-only agent
  ever arrives.

## Checks
- `scripts/check.sh` (`./mvnw -B test`): 1055 engine tests, 13 failures + 3 errors, all 16
  in `BellHookCommandDetachedShapeTest`, `ProcessTreesTest`,
  `ProjectAgentSessionWebSocketIntegrationTest`, `ProjectCheckoutServiceTest`,
  `ProjectWorktreesServiceTest`, `SessionRegistryReattachTest`, `WorktreeCleanupSweeperTest`
  and `WorktreeCreationServiceTest`. The same 16 fail identically on untouched
  `origin/main` (40ac832) in a throwaway worktree on this Mac (`setsid` is Linux-only,
  `/bin/true` is `/usr/bin/true` here, plus this host's git/gh state), so they are
  environment failures, not this diff's. Every test this task touched or added (104 across
  `MuseBellHookTest`, `InstalledAgentsStoreTest`, `ResumeIdScannerTest`,
  `AgentSessionTitlesTest` and the three `TerminalWebSocketHandler*Test`s) passes. CI on
  Linux is the authoritative run.

## Agents
- work: claude-code / claude-fable-5-1
