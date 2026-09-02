# 542 — Make /l-release ask for the version instead of guessing when omitted
Issue: #542

## Asked
When someone runs `/l-release` without giving `<version>`, the skill should stop and
ask for it instead of silently deriving a guess and proceeding. Today
`.claude/skills/l-release/SKILL.md`'s invocation line documents `<version>` as a
required argument, but the procedure never says what to do when it's left off — only
Step 1's gate validates a *given* version against `pom.xml`'s `<revision>`. That gap
let a session fill it in on its own: it read `pom.xml`, inferred `<version>`, defaulted
`<next-version>` to a patch bump, then hit an "Invalid tool parameters" error acting on
those guessed values — all without ever checking with the human first.

## Done when
- `.claude/skills/l-release/SKILL.md` has a new Step 0, before the existing gate: if
  `<version>` is not supplied on invocation, the skill stops and asks the human for it
  (it may suggest the `pom.xml`-implied version as a default to confirm, but never
  assumes it silently).
- The same treatment covers `<next-version>` at the point of invocation: if the human
  wants a non-default bump, that's asked for/confirmed up front rather than only
  surfaced as a "suggestion, not a silent decision" once Step 5 is reached.
- `./.t-workflow/scripts/consistency-check.sh` still passes.

## Explicitly not
- Does not change Step 1's existing gate logic for a *given* version, or any later
  step's procedure.
- Does not touch `.github/workflows/release.yml` or `scripts/generate-release-notes.sh`.

## Decisions made along the way
- The new step is a literal "Step 0" inserted before "1. Gate, before any write.",
  with steps 1–7 left unrenumbered — the file cross-references step numbers by number
  elsewhere (e.g. "ADR-106 D1 step 3"), so renumbering would touch prose outside this
  task's scope and risk breaking those references silently. (agent, driven run, 2026-09-02)
- `template-owned-paths.sh --list` flags this file as template-owned by its coarse
  "under a protected pattern" heuristic, but `.template-manifest.json`'s `files` map —
  the actual sync-lock ground truth — does not list it: it was written entirely in this
  repo by task #477, never shipped by the `t-workflow` template. Per the human's
  explicit direction, the manifest was treated as authoritative and this plan proceeded
  against `.claude/skills/l-release/SKILL.md` directly; the script's false positive is
  being filed as its own issue against `haninaguib-devtools/t-workflow`, tracked
  separately from this task. (human + agent, 2026-09-02)

## Deviations / notes
- none
