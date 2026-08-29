# Local slots

**Status:** binding convention.

Two places in the governance docs are per-repo **by design**, not template-owned:
`CONSTITUTION.md` §4 (stack & architecture) and `AGENTS.md` §Checks item 1 (the
build/test check command). Everything else in these files is template content, meant
to move the same way for every consumer. This document fixes the vocabulary and
boundary `/t-update` (`.claude/skills/t-update/SKILL.md`,
`docs/architecture/manifest.md`) honors when it replaces template content without
touching what a consumer wrote for itself.

## The marker

Each per-repo slot is wrapped in its own `<!-- local -->` … `<!-- /local -->` pair, one
marker per line, bracketing exactly the sentences that are consumer-specific — nothing
more. A sync tool replaces everything **outside** every marker pair in a template-owned
file with the incoming template text, and copies everything **inside** each pair
forward unchanged. A file may carry more than one marked region; each is independent.

This repo, being itself at Phase 0, keeps the neutral placeholder inside both markers
today: `(reserved: stack and architecture constraints — …)` in `CONSTITUTION.md` §4,
and `(none yet — no stack exists.)` in `AGENTS.md` §Checks item 1. A consumer repo
replaces that placeholder with its own real content once it adopts — its own stack
rule, its own build/test command — and a later template sync leaves that content alone.

**Why only item 1 of §Checks is marked, not the whole section**: items 2 and 3
(`./.t-workflow/scripts/consistency-check.sh`, the scope-diff review) and the CI-wiring sentence
that follows are pipeline machinery every consumer shares — they belong outside the
marker, so a sync always brings consumers current on them.

## Alias mechanism

`AGENTS.md` is the single source. `CLAUDE.md`, `GEMINI.md`, and
`.github/copilot-instructions.md` are filesystem symlinks to it
(`CLAUDE.md -> AGENTS.md`), not separate copies kept in sync by a process — there is
nothing to stamp and nothing that can drift between them, by construction. Editing the
session-start contract always means editing `AGENTS.md` itself; the three aliases exist
only so each agent finds its expected filename. `.agents/skills` is a separate symlink
to `.claude/skills/` and is unrelated to this mechanism.
