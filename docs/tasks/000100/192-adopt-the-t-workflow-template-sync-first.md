# 192 — Adopt the t-workflow template sync (first t-update)
Issue: #192

## Asked
This repo's copies of the delivery-system template files (the `t-*` skills, the
governance docs) have drifted from the template repo haninaguib-devtools/t-workflow,
which is now the single source of truth with a versioned sync mechanism (a `t-update`
skill, a per-consumer manifest pinning a template release tag, and a CI lock script).
Adopt it here: run the first sync to replace every template-owned file with the
canonical copy, carry this repo's per-repo content (the `CONSTITUTION.md` §4 stack
rules, the Maven check line in `AGENTS.md` §Checks) inside the template's
`<!-- local -->` slot markers, commit the manifest pinned to the template's release tag,
and wire the lock check into `.github/workflows/ci.yml` so a hand-edit to any
template-owned file fails CI.

## Done when
- The template's check script (`.t-workflow/scripts/check-manifest.sh`) passes on a
  clean tree, and fails after a deliberate hand-edit to a template-owned file (rehearsed
  and reverted, documented below).
- `diff` of every template-owned file against the pinned t-workflow tag is empty outside
  the marked local slots.
- `CONSTITUTION.md` §4 and the `AGENTS.md` Checks list read exactly as they do today —
  adoption changes where template text comes from, not what this repo has ratified.
- `.github/workflows/ci.yml` runs the lock check on every PR.

## Explicitly not
- No changes to template *content* — anything worth improving in a template file is
  upstreamed to t-workflow, never edited here.
- No changes to this repo's own files outside the template-owned set (e.g.
  `docs/architecture/releasing.md`, `docs/adr/002-rewrite-stack-and-agent-model.md`
  stay untouched).
- `.github/workflows/stale-branches.yml`, `.github/workflows/release.yml`,
  `scripts/check-stale-branches.sh` are not template-owned and are untouched.
- Pushing `t-workflow`'s own first release tag is not this task's job (it already
  exists — `v0.0.2`).

## Decisions made along the way
- (human, 2026-08-29) Drop the speculative GitLab/Jira command mappings in
  `docs/adapters/TRACKER.md`/`FORGE.md` and the corresponding `.gitlab-ci.yml`/`.gitlab/*`
  patterns rather than reintegrating them: `.gitlab-ci.yml`/`.gitlab/` never existed in
  this repo and `active-backend` was already `github`, so nothing functional is lost.
  Both files now match the template exactly.
- (human, 2026-08-29) Leave `CONSTITUTION.md` §3's `*(reserved: application surfaces —
  ...)*` line unmarked (no 3rd local slot invented here) — matches the template exactly.
  Documented as a forward risk: once #237 fills that line in, a *future* `t-update` could
  silently overwrite it, since it's outside the two slots `docs/architecture/
  local-slots.md` documents. Not this task's call to fix unilaterally; propose a 3rd
  slot upstream if #237 needs it.
- (human, 2026-08-29) Delete `.claude/skills/t-fix/` and `.claude/skills/t-wtree/`
  entirely, rather than keeping them as undocumented local extensions. Discovered
  mid-implementation: the newly-synced `.t-workflow/scripts/consistency-check.sh` hard-fails
  when a skill directory has no row in `AGENTS.md`'s pipeline table (neither did after
  the sync, since the template carries neither skill), and reading `docs/adr/
  002-trim-the-phase-0-workflow.md` showed the template deliberately retired both two
  ADRs ago — "machinery nobody exercises is not neutral — it is a standing cost." A
  worktree is now created with plain `git worktree add` by hand; there is no replacement
  for the no-issue `fix/` path (the newly-synced `ci.yml`'s `record` job no longer
  recognizes `fix/*` branches at all, so keeping `/t-fix` would have meant it silently
  broke CI the next time anyone used it). This touches `.claude/skills/t-wtree/**`,
  outside the plan's original Allowed paths (which only anticipated a one-line path fix
  to `t-fix/SKILL.md`, not deleting either skill) — approved in the moment rather than
  looping back through `/t-plan`, since both are still within the issue's own Scope line
  (`.claude/skills/`) and the decision was the human's, made live in this session.

## Deviations / notes
- `migrations/README.md` was copied even though `.t-workflow/scripts/
  template-owned-paths.sh` does not actually list it (its own `protected-paths.sh` has
  no `migrations/*` pattern) — t-workflow#20's own plan named it as an Allowed path, so
  the omission looks like an upstream gap between the documented convention and the
  executable list, not intentional. Not included in `.template-manifest.json`'s `files`
  map, consistent with the manifest being defined as exactly `template-owned-paths.sh`'s
  output — a future drift check will not cover this file. Worth a note upstream, not
  fixed here (template content).
- `.github/workflows/ci.yml` gained a `manifest` job running `.t-workflow/scripts/
  check-manifest.sh` on every PR (Done-when 4) — this repo's own addition, not template
  content: the template itself carries no manifest, so its own copy of this workflow has
  no equivalent job (`docs/architecture/manifest.md` §The CI lock says each consumer
  wires this in itself).
- `.t-workflow/scripts/github-bootstrap.sh`'s required-status-check list (`consistency`,
  `record`, `plan-gate`, `title-gate`, `blockers`, `cold-review`, `plumbing-test`) does
  not include `manifest` or `build` — matches the template exactly; `build` was already
  not required pre-sync, so this is consistent with the existing pattern, not a new gap.
  Running `github-bootstrap.sh` (a live GitHub settings change, no diff) is left for the
  human to decide whether/when to run, same as any other bootstrap re-run.
- **Done-when 2 ("diff of every template-owned file... empty outside the marked local
  slots") is not fully achievable for `.github/workflows/ci.yml` and `.gitignore`.**
  Verified by diffing all 48 template-owned files against the pinned tag with local-slot
  regions stripped: every file matches except these two, which carry the necessary
  `manifest`/`build` jobs and the Maven `target/` line respectively. Wrapping those in
  `<!-- local -->` markers (as done for `CONSTITUTION.md`/`AGENTS.md`) was considered and
  rejected for `ci.yml`: `<!-- local -->` is not valid YAML at the top level of a `jobs:`
  map, and `check-manifest.sh`'s marker regex is line-anchored HTML-comment syntax with
  no YAML-comment equivalent — that script is template-owned, out of this task's
  Non-goals to change. `.gitignore` could technically take the markers (a bare
  `<!-- local -->` line just becomes an inert, harmless ignore pattern), but for a
  two-line, low-severity addition (worst case: `target/` silently drops from
  `.gitignore` on a future sync, which is cosmetic and easy to notice/fix) it wasn't
  worth the ugliness. **Both will need their local content manually re-applied on every
  future `/t-update`, same as this task did** — recommend proposing upstream that
  `check-manifest.sh`/`t-update` support a YAML-comment-compatible marker form (e.g.
  `# <!-- local -->`) so non-Markdown template-owned files can carry a slot too.
