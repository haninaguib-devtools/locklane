# Local slots

**Status:** binding convention.

Five places across the pipeline's own files are per-repo **by design**, not
template-owned: `CONSTITUTION.md` §4 (stack & architecture), `AGENTS.md` §Checks item 1
(the build/test check command), `AGENTS.md` §The pipeline's slot after the `t-*` table
(consumer-local skill rows), and two spots in `.github/workflows/ci.yml`'s `checks`
job — its `timeout-minutes` value, and an extension point at the end of its `steps:`
list. Everything else in these files is template content, meant to move the same way
for every consumer. This document fixes the vocabulary and boundary `/t-update`
(`.claude/skills/t-update/SKILL.md`, `docs/architecture/manifest.md`) honors when it
replaces template content without touching what a consumer wrote for itself.

## The marker

Each per-repo slot is wrapped in its own `<!-- local -->` … `<!-- /local -->` pair, one
marker per line, bracketing exactly the sentences that are consumer-specific — nothing
more. A sync tool replaces everything **outside** every marker pair in a template-owned
file with the incoming template text, and copies everything **inside** each pair
forward unchanged. A file may carry more than one marked region; each is independent.

**In a comment-syntax file, the marker itself is a line comment.** A bare
`<!-- local -->` line is valid Markdown (used as-is in `CONSTITUTION.md` and
`AGENTS.md`) but not valid YAML — `ci.yml`'s markers are written `# <!-- local -->`
and `# <!-- /local -->`, at whatever indentation the surrounding YAML wants, so the
file parses the same with or without a consumer's own content inside the slot.
`.t-workflow/scripts/check-manifest.sh`'s marker match tolerates leading whitespace and
an optional `#` for exactly this reason — a bare marker and a commented, indented one
strip the same way.

This repo, being itself at Phase 0, keeps the neutral placeholder inside every marker
today: `(reserved: stack and architecture constraints — …)` in `CONSTITUTION.md` §4,
`(none yet — no stack exists.)` in `AGENTS.md` §Checks item 1, `(reserved: consumer-local
skills — …)` in `AGENTS.md` §The pipeline's skill-row slot, the template's own default
`timeout-minutes: 10` in `ci.yml`, and an empty region at the end of `ci.yml`'s `steps:`
list. A consumer repo replaces each placeholder with its own real content once it
adopts — its own stack rule, its own build/test command, its own table of local skill
rows, its own CI timeout, its own trailing build/manifest-check steps
(`docs/architecture/manifest.md` §The CI lock) — and a later template sync leaves that
content alone.

**The skill-row slot's own shape**: unlike the other slots, this one is meant to hold a
small Markdown *table*, not prose — the consumer's own `l-`-prefixed (or otherwise
non-`t-`) skills, one row each, mirroring the `t-*` pipeline table's own two-column
shape. It sits **after** the `t-*` table, never inside it: the marker itself is an HTML
comment, and an HTML comment breaks a GFM table if it lands inside one, so the slot is
placed following a short template-owned sentence pointing at it instead. The template
table itself — the `t-*` rows — stays outside any marker and template-owned, the same
as every other row-based content in these files.

**Why only item 1 of §Checks is marked, not the whole section**: items 2 and 3
(`./.t-workflow/scripts/consistency-check.sh`, the scope-diff review) and the CI-wiring sentence
that follows are pipeline machinery every consumer shares — they belong outside the
marker, so a sync always brings consumers current on them. The same reasoning bounds
`ci.yml`'s slots: only the two lines/regions a consumer actually customizes are inside
markers — every gate step, and the explanatory comments around them, is pipeline
machinery every consumer shares and stays outside, so a sync always brings consumers
current on it.

## The required-checks file

One per-repo customization is a **separate consumer-owned file** rather than a marked
region inside a template file: `.t-workflow/required-checks.local`, at the consumer
repo's root, lists the consumer's own required status checks — one CI context name per
line, blank lines and `#` comments ignored, the file simply absent when there is nothing
to add. `.t-workflow/scripts/required-checks.sh --list` prints the union of the
template's fixed contexts (`checks`, `cold-review`) and that file, and it is the one
place the list is computed: `github-bootstrap.sh` asserts that union in branch
protection, applying its "only once a real run exists on the trunk" guard to each
consumer context by name (`--asserted`), and `/t-ship` Procedure steps 3 and 5 flip the
live setting to it when a PR changes the list (#126).

It is a file and not a slot because the script that reads it is a template-owned
executable: a consumer editing between markers inside `github-bootstrap.sh` would be
editing the tool that syncs it, and `/t-ship` would have nothing to read but that
script's own text. The file sits under `.t-workflow/` but outside `.t-workflow/scripts/`,
so it matches no protected pattern (`.t-workflow/scripts/protected-paths.sh`), is never
in `template-owned-paths.sh --list`, is never hashed into the manifest
(`docs/architecture/manifest.md`), and is never touched by a sync — a consumer's list
survives every `/t-update` by construction, with no marker to preserve. A consumer that
had set a context by hand on the forge before this file existed moves it in via
`migrations/V2__required-checks-local.md`. This repo, being the template and not a
consumer, carries no such file.

## Alias mechanism

`AGENTS.md` is the single source. `CLAUDE.md`, `GEMINI.md`, and
`.github/copilot-instructions.md` are filesystem symlinks to it
(`CLAUDE.md -> AGENTS.md`), not separate copies kept in sync by a process — there is
nothing to stamp and nothing that can drift between them, by construction. Editing the
session-start contract always means editing `AGENTS.md` itself; the three aliases exist
only so each agent finds its expected filename. `.agents/skills` is a separate symlink
to `.claude/skills/` and is unrelated to this mechanism.

## Consumer ADR numbering

`docs/adr/` is one directory holding two provenances: template ADRs, synced in by
`/t-update`, and a consumer repo's own. The template owns numbers **000–099**; a
consumer's local ADRs start at **100**. The two must never share a number: the
consistency check resolves a decision reference like "ADR-008 D1" by globbing
`docs/adr/008-*.md` and taking the first match, so a local 008 sitting beside a
synced 008 makes every such reference ambiguous — and the template can mint any
number in its range in any future release (v0.0.8's new ADR-008 landed on a
consumer that had already used 008 locally, in exactly this way; that consumer
renumbered its local ADRs to 100+ at the sync). A consumer that numbered local
ADRs below 100 renumbers them out of the range at its next sync, updating its own
references; historical task records keep the numbers that were current when they
were written.
