# CONSTITUTION.md

The invariants of this project. Terse by design: rules live here, rationale lives in
`docs/adr/` and `docs/workflow.md`. Changing this file faces the highest scrutiny the
project has (see §Amendment below).

**Status note:** the project is at Phase 0 — only the delivery system exists. Its stack
and domain rules are **not yet decided** — sections marked *(reserved)* will be filled
by ADRs as decisions are ratified.

## 1. Delivery

1. `main` only moves by pull request. No exceptions.
2. Every change enters through the pipeline (`docs/workflow.md` §5): work starts from a
   tracker issue, carries a record riding in its PR, and reaches `main` only by PR with a
   human confirming the merge. Planning, an isolated worktree, and an independent review
   are chosen per task (ADR-001) — except on a protected surface (§3), where a plan and an
   independent review are required.
3. Git is the system of record for outcomes; the tracker and the forge are the venue for
   process. Which products fill those roles is a mechanical choice made in
   `docs/adapters/` (GitHub for both by default), not a constitutional one. Nothing
   binding exists only in an issue or PR thread — if it isn't merged, it isn't decided.
4. Squash commits are self-contained: goal, non-goals, outcome, and one or more
   `Task: #<id>` lines, each written from the task record it names — an ordinary merge
   carries exactly one; only `/t-drive`'s single PR closing an initiative's included
   children carries more than one, one line per included child (ADR-004).
5. Guardrails are never weakened to make work pass. A failing check is fixed by fixing
   the work. Loosening any gate is a protected change (workflow §11.3) with an ADR.

## 2. Decisions

1. One decision per file in `docs/adr/`, numbered, append-only; superseding means a new
   ADR. Every ADR carries rationale, alternatives, and revisit triggers.
2. An ADR is decided by merging its PR with the required approvals. Until then it binds
   nothing.
3. The operative one-line rule for each accepted ADR is added to this file or `AGENTS.md`
   with a pointer to the ADR.

## 3. Protected surfaces

Changes to the following always require a plan, an independent review, and heightened
approval.

**Heightened approval, in the solo phase, is the human's explicit confirmation at
`/t-ship`'s merge gate on a PR that carries a cold review with `readiness: ready`.** That
is the whole of it — there is no separate approval step to look for, and required
approvals on the forge stay at zero because a sole author cannot approve their own PR.
When a second maintainer joins, the bar rises to their approval on the forge as well, and
workflow §13 Q9 decides which surfaces need more than one.

This list has an executable twin: `.t-workflow/scripts/protected-paths.sh` decides the same question
for the skills and for CI. The two are one rule in two forms and change in the same task;
where they disagree, that is a defect to fix, not a judgment call to make.

- `CONSTITUTION.md`
- `AGENTS.md` (the session-start contract, and the only home of the check set), together
  with the aliases pointing at it — `CLAUDE.md`, `GEMINI.md`, `.agents/`, and
  `.github/copilot-instructions.md` (covered by `.github/` below): repointing an alias
  replaces an agent's whole session-start contract
- `docs/adr/`
- `docs/workflow.md` (the cross-stage rules and §1 principles)
- `.claude/` — the whole directory, not only `.claude/skills/` (the workflow's executable
  form). Sibling files configure the agent that executes it: `.claude/settings.json`
  defines hooks, which run arbitrary shell on tool calls, and permission allowlists;
  `.claude/agents/` defines subagents. Anything that changes what an agent does when it
  runs is as load-bearing as the skills themselves
- `.mcp.json` and `.cursor/` (the same argument, for other agents' executable config)
- `docs/adapters/` (the tracker/forge backend maps the skills execute through)
- `docs/architecture/` (binding conventions the skills execute against)
- `.github/` (CI, CODEOWNERS, rulesets)
- `.t-workflow/scripts/` (the mechanical checks and settings-as-code)
- `installer/` (the one-command bootstrap that generates every new project from this
  template — a defect here is inherited by repositories nobody in this one will review)
- `docs/tasks/TEMPLATE.md` and `docs/tasks/README.md` (the shape of every future record —
  the individual records under `docs/tasks/<bucket>/` are not protected)
- `.gitignore` (what it hides never reaches review)
- `README.md` (§3's genesis exception delegates its wording to §Bootstrapping there, so
  that file carries binding content)
- `LICENSE` (the terms everything else in the repository is offered under)

*(reserved: application surfaces — data-privacy paths, contracts, migrations, grants,
audit — to be added when the application exists.)*

**Genesis exception.** A repository started from this template is bootstrapped outside
the pipeline: the placeholder fills named in `README.md` §Bootstrapping and the first
commit are made directly, in that order, before any tracker, PR, or branch protection
exists to route them through. Either route performs genesis — `installer/` doing it in one
command, or a person doing it by hand — and the exception is identical in both: it covers
exactly those placeholder fills and that first commit, and nothing else. **The exception
ends when that first commit is pushed** — one end-point, stated the same way in
`README.md` §Bootstrapping. Every *edit to the tree* after that push goes through the
pipeline, including further edits to those same files. Running
`.t-workflow/scripts/github-bootstrap.sh` is not such an edit — it changes settings on the forge and
produces no diff — so it needs no task, before or after the push. The exception never
covers a second round of "just this once".

The exception belongs to the repository being *created*, and covers only its own genesis.
It never covers work on the tooling that creates one: in a repository that ships an
installer, changing that installer is ordinary protected work, planned and reviewed like
any other change to this list.

## 4. Stack & architecture

<!-- local -->
1. Spring Boot engine + Angular PWA client + SQLite for durable non-binding state, with
   one persistent PTY (pseudo-terminal) session per git worktree, reattachable from any
   browser, from anywhere — never a job queue (ADR-100).
2. A console-created, per-issue worktree is removed automatically once its issue is
   confirmed closed, its git status is clean, and no console session is attached — a
   narrow, guarded exception to the pipeline's own "left alone permanently" default
   (ADR-102). Once such a worktree is removed, its local `wip/<id>-<slug>` branch is
   deleted too, but only via `git branch -d`: a fully merged branch goes, an unmerged
   one is refused and left alone (ADR-103).
3. Reserved protected surfaces, per ADR-101: once the ratified multi-user tenancy and
   authorization model is implemented, these application surfaces join §3's list — the
   Flyway migrations under `engine/src/main/resources/db/migration/` and
   `engine/src/main/java/dev/locklane/engine/persistence/migration/`, the
   `owner_user_id` ownership/authorization checks in `ProjectController` and
   `ProjectRepository`, the account and authentication code in
   `EngineUserDetailsService` and `SecurityConfig`, and the future admin
   user-management controller. They are not yet backticked into §3's bullet list or
   into `.t-workflow/scripts/protected-paths.sh`: check 9 of
   `.t-workflow/scripts/consistency-check.sh` requires every backticked §3 path to
   already be enforced there, and this code does not carry the ratified checks yet.
   The task that lands each surface (#238–#242) adds its §3 bullet and its
   `protected-paths.sh` pattern together, per §3's own "one rule in two forms"
   invariant.
4. A project-console worktree (no issue of its own) is removed on tab close, and by
   the same periodic sweep as a backstop, once its session has ended, its HEAD is
   detached (a checked-out branch means the console outgrew scratch and is left
   alone), its git status is clean, and its HEAD is an ancestor of `origin/main` — a
   second, distinct guarded exception to ADR-005, alongside point 2's rather than
   folded into it (ADR-104).
5. A project is visible and operable only to the account that owns it, and a
   worktree/console session only to the owner of its project — no role, administrator
   included, is exempt; administrators manage accounts and nothing more (ADR-105,
   superseding the administrator exemption in ADR-101 Decisions 1 and 6).
<!-- /local -->

## Amendment

- This file changes only by PR at the heightened approval bar, normally alongside an ADR.
- Workflow principles change by ADR; workflow mechanics change by ordinary PR
  (`docs/workflow.md` §11).
