# 861 — Carry the agent's own message into the waiting notification via terminal notification sequences
Issue: #861

## Asked
Let the notification say what the agent is waiting for. Several agent CLIs can emit the standard terminal notification escape sequence (OSC 9, and OSC 777) carrying a short message, and the engine could pass that text through on the attention event as the notification body, for example "Merge PR #851 into main?" instead of "Agent on #851 is waiting". It is a terminal standard rather than a per-agent heuristic, so it stays inside #130's rule against parsing agent output. Includes deciding how Locklane tells each CLI that its terminal accepts the sequence, since Claude Code chooses its channel from the terminal program it detects.

## Done when
*(pinned by `/t-plan` — the bell-based notification has shipped: #853 and #854-#859 plus #862 are closed)*
- The engine parses OSC 9 notification text and OSC 777 notify sequences generically in the drain scan (never any agent prompt text or screen contents), excluding the OSC 9 progress form and never treating an OSC terminator BEL as a bell
- An OSC notification marks the session waiting with reason bell and its message rides the consoleAttention event, the connect-time snapshot, and the server-side attention listener; cleared only on active
- The in-app browser notification and the Web Push payload use that message as the body when present, falling back to today's issue-title and project-name body otherwise
- How each CLI is told the terminal accepts the sequence is decided per CLI against the installed CLIs and recorded; a CLI with no such signal keeps its bare-bell hook and today's body
- scripts/check.sh passes (engine and client suites)
- Human check on a real install: an agent emitting a notification sequence shows its own message in the waiting notification, and one emitting none keeps today's body

## Explicitly not
- Parsing any agent's prompt text or screen contents.

Split from: #853

## Decisions made along the way
- OSC 9 `9;4...` is progress, never a notification: excluded by prefix, covered by a test that also proves title OSCs still never ring.
- An OSC notification marks waiting with reason bell on its own (its terminating BEL stays punctuation per #233); a bare BEL preserves the stored message so bell-before-OSC and OSC-before-bell in one chunk pair up; active clears.
- Message cap 200 chars, single line, C0/C1 to spaces, never logged above DEBUG.
- TERM_PROGRAM=iTerm2.app is advertised on agent launches only, putIfAbsent so a host terminal that already named one wins; shells untouched. iTerm2.app names OSC 9's own terminal and the engine parses both OSC 9 and OSC 777, so either channel lands.
- Codex/OMP/OpenCode bell hooks stay bare-bell: their event payloads carry no reliable human message to compose an OSC from (Codex notify JSON, OMP agent_end willContinue/tool events, OpenCode session.idle/permission.ask), so the message arrives via each CLI's native OSC emission once the terminal is advertised — an accepted outcome the plan named, not a gap.
- Client replaces the body but never the title; a second bell with a new message re-emits and replaces the shown notification (constructor path closes the previous handle first; SW path replaces by tag).

## Deviations / notes
- none

## Checks run
- `scripts/check.sh` (straight, host env) — FAIL: `ProjectCheckoutServiceTest` (3) and `ProjectAgentSessionWebSocketIntegrationTest` (1) fail because the host exports `GH_TOKEN` and `GIT_CONFIG_*`, which leak into tests that assert a token-free / helper-free git environment. Unrelated files (no overlap with this diff's pty/push/ws/client paths); out of scope, proposed as a follow-up in the report, not fixed here.
- `env -u GH_TOKEN -u GIT_CONFIG_COUNT -u GIT_CONFIG_KEY_0 -u GIT_CONFIG_VALUE_0 -u GIT_CONFIG_KEY_1 -u GIT_CONFIG_VALUE_1 scripts/check.sh` — PASS: full `./mvnw -B test` (engine + client) green on the same tree.
- Focused engine suites (PtySessionAttentionTest, SessionRegistryAttentionListenerTest, SessionRegistryWaitingSessionsTest, PushNotifierTest, EventsWebSocketHandlerTest, TerminalWebSocketHandlerLaunchCommandTest): 52 run, 0 failures.
- Focused client specs (attention-store, notification.service, events): 72 SUCCESS.
