# 681 — Support omp as a fourth known coding agent
Issue: #681

## Asked
Locklane already treats three terminal AI coding agents — Claude Code, Codex, and
OpenCode — as first-class: it detects whether each is installed, captures a session's
resume id from its output, offers it in the console's agent picker, and (for the first
two) surfaces its usage/token consumption in the usage widget. Add `omp` (the CLI for
oh-my-pi, https://omp.sh — a terminal coding agent in the same category) as a fourth
member of that same set, so it shows up everywhere the other three do.

## Done when
- `InstalledAgentsStore.KNOWN_AGENTS` (`engine/src/main/java/dev/locklane/engine/agent/InstalledAgentsStore.java`)
  includes `"omp"`, detected the same way as the other three (PATH scan at boot,
  `InstalledAgentDetector`).
- `ResumeIdScanner` (`engine/src/main/java/dev/locklane/engine/pty/ResumeIdScanner.java`)
  recognizes omp's own resume/session-id command syntax and id format (per
  `omp.sh/docs/cli`), the same way it already does for `claude --resume`, `codex resume`,
  and `opencode --session`.
- The client's `Agent` union (`client/src/app/services/agent-store.ts`) includes
  `'omp'`, and every place that enumerates it is updated to match: the agent-picker
  component/template (`client/src/app/components/agent-picker/`), `console-labels.ts`'s
  tab tagging, and the call site where the picker's chosen agent name is typed as the
  literal launch command into a new console session.
- The usage widget question is explicitly resolved, not silently skipped: either omp
  has a locally-queryable usage/token source comparable to `ClaudeUsageProvider` /
  `CodexUsageProvider` / `OpenCodeUsageProvider` (`engine/src/main/java/dev/locklane/engine/usage/`)
  and it is wired in the same way, or the task record states why it does not (omp is
  bring-your-own-provider across 40+ model backends, so there may be no
  locklane-trackable usage the same way the other three have their own subscription
  usage) and the usage widget is left untouched for omp.
- `./mvnw -B test` and the client's own test suite both pass.

## Explicitly not
- No omp-specific features beyond parity with the existing three agents (e.g. no new
  UI surface unique to omp).
- No change to the shape of `InstalledAgentDetector`, `ResumeIdScanner`, or the
  `UsageProvider` interface themselves — omp slots into the existing pattern.

## Decisions made along the way
- none

## Deviations / notes
- none
