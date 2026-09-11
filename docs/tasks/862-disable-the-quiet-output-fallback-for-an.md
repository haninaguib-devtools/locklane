# 862 — Disable the quiet-output fallback for an agent launched with a bell hook
Issue: #862 · Part of: #853

## Asked
Switch the quiet-output fallback off for a session whose agent rings the bell itself. The engine marks a PTY session as waiting on two signals (#130): a bell byte in its output, and output gone quiet for `PtySession.QUIESCENCE_THRESHOLD_MS` (three seconds) with no input since. The quiet rule exists for agents that never ring the bell, and it is the source of the dot's false positives: an agent pausing between output chunks, or a command finishing in a shell, is just as quiet as an agent waiting for the user. Once an agent CLI is launched with an injected bell hook, it has a precise signal and the quiet rule adds only noise, so the engine disables the quiet check for exactly those sessions and keeps it, at its current three seconds, for every session launched without one: plain shells, agents whose hook task has not landed, and anything the engine does not recognise. The decision is made where the launch command is composed, since that is the one place that knows whether a hook was injected, and it flows to `PtySession` as a constructor-time flag; nothing changes the threshold itself. Raising the delay instead was considered and rejected: it postpones the false positives without removing them and slows the true signal for every unwired tab.

## Done when
- `PtySession` takes a flag saying whether the quiet-output fallback applies; with it off, `checkQuiescence` never marks the session waiting, and a bell still does; with it on, behaviour is exactly today's. An engine test covers both.
- The flag is set from the same place `TerminalWebSocketHandler` composes the launch command: on for a shell or an unrecognised command, off for a launch that carried an injected bell hook. It is off only for an agent whose hook task has landed and been checked in a real tab; a unit test asserts the flag per command.
- `PtySession.QUIESCENCE_THRESHOLD_MS` is unchanged.
- In a Locklane agent tab running Claude Code, an agent that pauses mid-turn with no output no longer shows the amber dot; the dot still appears the instant the turn ends; a shell tab behaves as today.
- `./mvnw -B test` passes.

## Explicitly not
- Changing the three-second threshold, or the quiet rule for any session launched without a hook.
- A global switch; the fallback is disabled per launch, per verified agent.
- Any client change.

## Decisions made along the way
- **Which commands count as "an injected bell hook landed"**: `TerminalWebSocketHandler`
  already has exactly this predicate — its private `isAgent(cmd)`, used until now only
  to gate #537's seeded-launch branch, is true for precisely `claude`/`codex`/
  `opencode`/`omp` and false for everything else (a shell, `null`, or any other
  command). Those are exactly the four whose bell-hook task has landed (#855-#858):
  `claude`/`codex`/`omp` carry it as an injected argv element composed right here;
  `opencode` carries no argv marker of its own (#858 — OpenCode has no launch-time way
  to load a local plugin file), but this same class already depends on
  `OpenCodeBellPlugin` purely so its global-plugin-directory install is guaranteed to
  have run by the time any `opencode` launch reaches this code, so it counts too. The
  flag passed down is simply `!isAgent(cmd)`, computed once in `resolveLaunch` before
  either of its branches, so a seeded launch and a plain one agree.
- **Where the flag lives and how it threads through**: added as a `boolean` field on
  `PtySession`, set once at construction (a session's whole lifetime, never
  reattached), guarded at the top of `checkQuiescence(long)` so a bell (`markBell()`)
  is entirely untouched by it. `SessionRegistry.attach` gained one new overload
  (`..., Map<String, String> extraEnvironment, boolean quiescenceFallbackEnabled)`)
  rather than changing an existing one's signature: every other overload and every
  existing caller (in and out of this task's Scope — several test files elsewhere in
  the engine construct sessions this way) keeps compiling unchanged and keeps today's
  behaviour, since they all default to `true` through one delegating call. Likewise
  `PtySession` kept its existing 6-arg constructor as a `true`-defaulting overload of
  the new 7-arg one, so every pre-existing direct-construction test in
  `PtySessionAttentionTest`/`PtySessionEnvironmentTest` needed no change at all.
  `TerminalWebSocketHandler.Launch` (already the single funnel from `resolveLaunch` to
  the one production call to `attach`) gained a third field,
  `quiescenceFallbackEnabled`, for the same reason — no test constructs `Launch`
  positionally, only through its accessors, so this was a compatible addition too.
- **Reattach is untouched**: the flag only matters inside the `computeIfAbsent` lambda
  that constructs a brand-new `PtySession` — a reattach to an already-running session
  reaches the same session with whatever flag it was created with, exactly like
  `launchCommand`/`extraEnvironment` already work for reattach, so nothing needed to
  change there.

## Deviations / notes
- **The Done-when's "checked in a real tab" qualifier is not literally satisfied for
  any of the four agents yet**: every one of #855/#856/#857/#858's own task records
  says its manual real-browser/real-tab verification was not completed, for the same
  sandbox reason recorded again below. Read narrowly, that would mean this task's own
  flag can never legitimately turn off for anyone until each of those manual checks
  eventually happens. Read as intended — this task's own Done-when bullet 4 is itself
  that check, scoped to Claude Code as the representative case, and it is the one
  actually gating "checked in a real tab" here — the implementation disables the
  fallback for all four landed-hook agents now, with the manual verification recorded
  as an open human check below, exactly the same pattern #855-#858 themselves
  established (code shipped, browser check pending) rather than blocking the whole
  initiative on a check none of them could run in this sandbox either.
- The Done-when's manual check — "in a Locklane agent tab running Claude Code, an
  agent that pauses mid-turn with no output no longer shows the amber dot; the dot
  still appears the instant the turn ends; a shell tab behaves as today" — was **not
  completed in a real tab**, the same sandbox reason recorded on #855/#856/#857/#858
  (no controlling terminal, no browser). What was verified instead: `PtySession`'s own
  unit tests directly exercise `checkQuiescence` with the flag off (never marks
  waiting) and on (unchanged), and with a real bell byte still marking waiting
  regardless of the flag; `TerminalWebSocketHandler`'s own unit test asserts
  `resolveLaunch(...).quiescenceFallbackEnabled()` is `false` for every one of
  `claude`/`codex`/`opencode`/`omp` and `true` for a shell, no command, and an
  unrecognised one.
- `./mvnw -B test` (`scripts/check.sh`'s configured check) needed `GH_TOKEN`/
  `GIT_CONFIG_*` unset first — the same pre-existing console-environment issue noted
  on earlier tasks in this initiative, unrelated to this change. One further flake was
  observed and confirmed pre-existing and unrelated: `CodeServerServiceTest
  .closingTheSessionStopsItsCodeServerProcess` failed once in the full 989-test run
  (a code-server process not accepting connections within its own short timeout) and
  passed cleanly when re-run alone; this task touches no code-server file.
