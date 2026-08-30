# 373 — Show auto-generated titles for resumable console sessions
Issue: #373 · Part of: #371

## Asked
The past-conversation list on an issue's Overview tab, and the one #372 just added to
the project console page, show each session as a tool name and a captured timestamp —
hard to tell apart once there is more than one or two. All three supported CLIs already
generate a short human-readable title for a conversation; surface it in the list, and
fall back to today's display whenever a title isn't available.

## Done when
- Given a `(tool, resumeId, workingDirectory)` triple, a per-tool lookup returns an
  optional title, never throwing when the title is missing, the CLI predates the
  feature, or the tool is not installed.
- Both resume-session responses — the project's and the issue's — carry the optional
  title.
- `SessionListComponent` shows the title when present and falls back to exactly today's
  display (tool + captured time) when absent.
- Manual check: hold a real conversation with each installed CLI long enough for it to
  generate a title, close its console, and confirm the list shows that title.
- `./mvnw -B test` and the client's test suite both pass.

## Explicitly not
- Making Codex title lookup work against CLI versions older than v0.150.0 — those
  sessions keep the timestamp fallback.
- Letting a user edit or rename a title from this UI.

## Decisions made along the way
- `ConsoleSessionTitles.titlesFor` takes a batch of sightings rather than resolving one
  at a time. The three mechanisms cost wildly different amounts: Claude is a small file
  read per conversation, Codex is one index file for all of them, and OpenCode is a
  *process*. A per-row loop would spawn one `opencode` per listed conversation; the
  batch runs it once per distinct directory (haninaguib, 2026-08-30).
- The title is carried on `WorktreeController.ResumeSessionView` only, not on
  `ConsoleResumeSessionRecord`. That record mirrors a `console_resume_sessions` row and
  no title is stored there — putting a computed field on it would misrepresent what the
  table holds. Since #372 both listings already return the one view type, so both
  responses carry the title from the single change (haninaguib, 2026-08-30).
- Claude and OpenCode both key a stored conversation by the directory it ran in, so a
  title cannot be found without that directory. #372's record-or-derive resolution was
  extracted out of `ProjectConsoleService.reopenSession` into a public
  `conversationDirectory`, and an issue-side counterpart added to
  `WorktreeCreationService`. Reopening a conversation and reading its title now ask the
  same question of the same code (haninaguib, 2026-08-30).
- Claude's transcript-folder naming (every character that is not a letter or digit
  becomes `-`) was derived by reading the real directories under `~/.claude/projects`
  on this machine rather than guessed — it reproduces all 148 of them exactly, dots and
  slashes each contributing one dash and existing dashes surviving (haninaguib,
  2026-08-30).
- Both CLI home directories are constructor-injected, defaulting to `CLAUDE_CONFIG_DIR`
  / `CODEX_HOME` and then `~/.claude` / `~/.codex`. That is what makes the lookup
  testable against a temp directory instead of the developer's real conversation
  history (haninaguib, 2026-08-30).
- OpenCode is asked through its documented CLI (`opencode session list --format json`),
  never its SQLite store, as the issue specifies — with a 10-second bound, so a hung or
  very slow CLI degrades to "no title" instead of holding the HTTP response open
  (haninaguib, 2026-08-30).
- In the list, a title takes the timestamp's place in the row rather than sitting
  alongside it — the rail is narrow, and two lines of metadata per row read worse than
  one. The captured time is not lost: it moves onto the title's own tooltip. The tool
  label stays either way, since it is what a reopened console gets launched with
  (haninaguib, 2026-08-30).
- `ResumeSession.title` is required-and-nullable (`string | null`) rather than optional,
  so a client fixture that forgets it fails to compile instead of silently testing a
  shape the engine never sends. That is why four client spec files appear in this diff
  (haninaguib, 2026-08-30).

## Deviations / notes
- Issue #373's Scope line was amended before implementation, on the owner's explicit
  ask in this session: it named neither the shared client model
  (`client/src/app/models/issue.model.ts`, which must carry the title for the component
  to render it) nor any test path, so the task could not be implemented or covered
  inside it. A second, narrower amendment followed once the required-and-nullable
  `title` field turned out to touch four client spec fixtures. Both amendments are
  noted on the issue itself, and every path in this diff sits inside the amended Scope.
- **Only Claude's mechanism was verified against real data on this machine.** Its
  `ai-title` lines were read out of actual transcripts under `~/.claude/projects`.
  The other two could not be: the Codex CLI installed here is 0.149.1, below the
  v0.150.0 that first writes `session_index.jsonl` (the file does not exist), and there
  are no OpenCode sessions in any directory, so `opencode session list --format json`
  returns empty. Both readers are written to the shapes the issue documents and covered
  by tests against those shapes, but their real-world output is unconfirmed here — which
  is exactly what the issue's manual check exists to settle.
- Not verified in this session: the manual check in the issue's Done-when (a real
  conversation with each installed CLI, long enough to be titled, then confirming the
  list shows that title). It needs a human at a running app, with a Codex ≥ v0.150.0 for
  that third of it.
