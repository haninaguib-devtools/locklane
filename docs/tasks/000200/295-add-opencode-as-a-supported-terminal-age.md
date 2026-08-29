# 295 — Add OpenCode as a supported terminal agent (server)
Issue: #295 · Part of: #294

## Asked
Teach the engine to launch, resume, and report usage for OpenCode terminal sessions, the
same way it already does for Claude Code and Codex. "Claude" and "Codex" are each
hardcoded independently across the terminal launcher, the session scanner, and the usage
subsystem; this task extends that same hand-implemented pattern to a third CLI,
"OpenCode".

## Done when
- `?cmd=opencode` on the terminal WebSocket launches an OpenCode session and builds its
  resume command, matching the existing `claude`/`codex` handling in
  `TerminalWebSocketHandler`.
- `ResumeIdScanner` recognizes OpenCode's process name and resume-ID format (an
  `OPENCODE` constant, a resume-command regex, and a `toolHintFor()` entry), mirroring
  `CLAUDE`/`CODEX`.
- A `UsageProvider` implementation exists for OpenCode (mirroring
  `ClaudeUsageProvider`/`CodexUsageProvider`, paired with its own token/credentials
  source) and is wired into `UsageConfig`/`UsageService`, so OpenCode usage is fetched
  and surfaced through the same usage API the client already consumes.
- Existing Claude/Codex launch, resume, and usage behavior is unchanged.
- `./mvnw -B test` passes.

## Explicitly not
- Redesigning `UsageService`/`UsageSnapshot`'s two-provider handling beyond what adding
  OpenCode requires — a third named field (`opencode`), same shape as `claude`/`codex`,
  not a map/list refactor.
- Any client-side (Angular) change — tracked as #296.
- Writing an architecture doc for the multi-CLI-agent pattern.

## Decisions made along the way
- OpenCode's own CLI has no `resume` subcommand; it resumes via a flag:
  `opencode --session <id>` / `-s <id>` (confirmed against `opencode-ai@1.18.25 --help`).
  Mirrors Claude's flag form (`claude --resume <id>`), not Codex's subcommand form.
- OpenCode session ids are `ses_`-prefixed (ULID-based per public source references), not
  UUIDs like Claude/Codex's captured resume ids. `RESUME_ID` (WebSocket handler) and the
  scanner's id patterns were generalized to accept either shape rather than forking the
  whole validation path. (Hani, 2026-08-28)
- OpenCode's credentials file is `~/.local/share/opencode/auth.json` (confirmed via
  `opencode-ai@1.18.25 auth list`, which prints that exact path with 0 credentials in
  this sandbox). (Hani, 2026-08-28)
- **OpenCodeUsageProvider always resolves to `ProviderUsage.unavailable()`.** OpenCode's
  own account model has no five-hour/weekly percent-of-window quota the way Claude/Codex
  subscriptions do — its Zen billing is a pay-as-you-go dollar balance with no time-based
  reset (confirmed via OpenCode's own Zen docs: "no usage limits or weekly quota... no
  5 hour window, no weekly cap and no tier"). No documented or discoverable
  balance-check endpoint exists to query programmatically, and unlike the original
  Claude/Codex providers (#137, found by inspecting a live, logged-in installation) there
  is no live OpenCode+Zen account available here to reverse-engineer one against safely.
  Raised to the human at `/t-work` time; decided: ship the provider structurally (token
  source reads the real credentials file, wired into `UsageConfig`/`UsageService`/
  `UsageSnapshot` exactly like Claude/Codex) but never fabricate an unverified endpoint —
  `fetch()` always degrades to unavailable, which is the honest representation of "no
  window-shaped quota exists to show." A real endpoint can replace this later without
  touching any other file in this diff. (Hani, 2026-08-28)

## Deviations / notes
- None beyond the OpenCodeUsageProvider decision above.
