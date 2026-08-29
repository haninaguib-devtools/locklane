# Delivery workflow

**Status:** adopted (Phase 0) · baseline decided by ADR-001, 2026-08-24

A short overview of how a change moves from idea to `main`. The instructions live in the skills
(`.claude/skills/`); this page explains the shape. Section numbers survive because append-only
ADRs cite them.

## 1. Principles

1. **Review is the scarce resource** — code is cheap; trustworthy verification is not.
2. **Git records outcomes; the tracker and forge host process.** If it isn't merged, it isn't decided.
3. **`main` only moves by pull request**, and a human confirms every merge.
4. **Rules the build enforces beat rules the model must remember.**
5. **Ceremony is spent where it pays** — the stages between issue and merge are chosen per
   task, and protected surfaces (`CONSTITUTION.md` §3) keep the full treatment (ADR-001).

## 2. What lives where

Knowledge lives in the repository, in every clone: `CONSTITUTION.md`, `AGENTS.md`, `docs/adr/`,
`docs/architecture/`, `docs/tasks/`, `.claude/skills/`, `.github/`. Process lives in
the tracker and the forge — issues, PRs, reviews, CI runs — which is reconstructable, not
load-bearing. The skills reach both only through named operations that
`docs/adapters/TRACKER.md` and `docs/adapters/FORGE.md` map to the active backend
(GitHub for both today; a future backend adopts by editing those two files). How that
knowledge reaches a repo generated from this one is its own convention
(`docs/architecture/manifest.md`): a pinned release tag, and `/t-update` to move it
forward.

## 3. Task identity

A task's ID is its **tracker issue number** — issues and PRs share one atomically minted
sequence, so a bare `#142` is never ambiguous. Everything inherits it: branch `wip/142-<slug>`,
record `docs/tasks/000100/142-<slug>.md`, squash-commit line `Task: #142`. Records shard into
**ID buckets of 100** (ADR-001 §D4): the directory is the ID rounded down to the nearest 100,
zero-padded to 6 digits, so a bucket never exceeds 100 files however fast tasks open, and the
path is computable from the ID alone. If numbering ever restarts, the new counter is bumped past
the old maximum (§10, mitigation 5), so paths never collide. The PR number is deliberately
unimportant as an *identifier* — nothing is named after it — but it is still needed to
address a PR, and every skill resolves it the same way: list PRs whose head branch is
`wip/<id>-*`.

## 4. The task record

Every task carries a record, created on the branch when work starts and **merged atomically with
the change it describes** (`docs/tasks/TEMPLATE.md` is the shape). It holds what code cannot:
what was asked, what was excluded, the decisions and deviations along the way. Being in the diff
it is reviewed too — *does this record honestly describe this change?* Intent changes there
rather than in the issue body once work starts.

## 5. The pipeline

The stage-by-stage table lives in `AGENTS.md` §The pipeline (one row per skill); the
skills themselves are the instructions. This section carries only the rules that span
stages. One stage-level note with no better home: `/t-status` derives everything from
tracker/forge queries and git — status is never a maintained file.

**Never optional:** an issue before the work, a record riding in the PR, a human-confirmed PR
into `main`. **Chosen per task (ADR-001, trimmed by ADR-002):** plan, cold review — except on a
protected surface (`CONSTITUTION.md` §3), where plan and review are both required, decided from
the paths the diff touches, not from a label. A worktree stays available to any task that wants
one (`git worktree add`, or one a launching engine creates); it is no longer a pipeline stage
with a skill of its own. **Nothing chains:** each stage names the next command and stops, and a
`not-ready` review blocks shipping either way.

**Only blocker and high findings hold a review open**; the rest are posted for the human to
fix, defer, or accept, and judgments no command can settle travel to the merge question, where
confirming acknowledges them. **Cancellation is a stage, not a cleanup:** its reason and every
neighbour's disposition land on the issue before anything is destroyed (ADR-001 §D3).

## 6. Work larger than one PR

The unit is the **reviewable merge**; the answer to bigger work is never a bigger PR.
**6.1** Work spanning several PRs gets a tracking issue holding the intent and its children; it
has no branch or record of its own, and a child that must wait is opened anyway with a
native blocked-by dependency set on it. **6.2 "Releasable":** every merge leaves `main` green and deployable as a
set at the next release cut — code unwired, endpoints behind permissions nobody holds, handlers
before emitters — but need **not** satisfy runtime backwards compatibility, since window deploys
mean old code never runs against the new schema. **6.3** Migrations are separate PRs from
feature logic; a destructive one fails CI on its own branch until the code stops using what it
drops, so the build dictates the order. **6.4** Cross-cutting work splits one task per module.
**6.5 Driving an initiative** ([ADR-004](adr/004-autonomous-initiative-driving.md)) is the
opt-in alternative to 6.1's one-PR-per-child default: `/t-drive <initiative-id>` chains
`/t-plan`+`/t-work`+`/t-review` across an initiative's children on one integration branch,
merging each child into it once that child's own review authorizes the merge, excluding —
never auto-cancelling — a child that still fails after one bounded retry, then stopping once
for the human's single confirmation on the initiative's combined PR to `main`. It is the one
explicitly-invoked exception to §5's "nothing chains" rule (ADR-001 D1), narrowed to exactly
this case; every other stage still stops and names the next command.

## 7. Design work

Design tasks use the same stages with a document as the deliverable, and **merging is the act of
deciding** — one subject per doc PR, because a single "here is the whole design" PR is
undiscussable. Discussion before a draft is free: explore (nothing binding), converge (a
proposal PR), ratify (merge). Review asks whether a design is consistent, unambiguous, and
complete against the known hard cases — not whether it is *right*.

## 8. Decisions

One ADR per file in `docs/adr/`, numbered and append-only: context, decision, rationale,
alternatives, consequences, revisit triggers. Superseding means a new ADR, and the ceremony is a
PR — *Proposed* on arrival, *Accepted* on merge. ADRs carry rationale, read on demand;
`CONSTITUTION.md` and `AGENTS.md` carry the terse rule and a pointer. **The promotion rule:**
anything durable settled in a PR thread lands in the record, an ADR, or the docs before merge.

## 9. Mechanical enforcement

In force today, *mechanically*: branch protection on `main` (PRs only, squash merges, no
force pushes); CI running `.t-workflow/scripts/consistency-check.sh` and the record-present guard on
every PR (`.github/workflows/ci.yml`); the repo's `delete_branch_on_merge` setting
(`.t-workflow/scripts/github-bootstrap.sh`) deleting a merged branch's remote copy without any
skill-side step. A stale local worktree or branch left behind by `/t-ship`/`/t-cancel`
(ADR-002) is left alone permanently (ADR-005) — nothing cleans it up, and a human removes
one by hand only if it is ever actually in the way. Held by convention, not by machinery:
self-contained squash commit bodies written from the record — the forge enforces squash
*merging*, nothing checks what the message says. Still to come: CODEOWNERS approval on protected paths, with
`CONSTITUTION.md` and `docs/adr/` at a heightened bar (§13 Q9), and required approvals
above zero, which needs a second maintainer. **Platform constraint:** on a private
repository these need a paid GitHub plan, so until then PR-only `main` runs on convention;
`.t-workflow/scripts/github-bootstrap.sh` applies what the plan permits.

## 10. Resilience: losing the GitHub account

Losing GitHub loses verification evidence and conversational texture, **never knowledge**: code,
constitution, ADRs, architecture docs, and every record live in git. Mitigations, in order: (1)
self-contained squash commits; (2) settings as code; (3) the promotion rule; (4) a periodic
export of issues and threads, including sub-issue and dependency relations — parent/child and
blocked-by/blocking live only in the tracker's structured fields since ADR-003 moved them out
of issue bodies, so an export that reads body text alone would silently lose every relation it
covers; (5) bumping a new account's issue counter past the old maximum if
numbering ever restarts. One exception to the guarantee: a cancelled task's reason lives only in
its close comment (ADR-001).

## 11. Evolving the workflow

**11.1** The skills, CI config, and this page live in the repository, so changing them is an
ordinary task. **11.2 Two speeds:** principles (§1, the protected-surface list, the shape of the
pipeline) change by ADR; mechanics change by ordinary PR and must stay cheap, because a process
too expensive to fix gets routed around. **11.3 The ratchet:** tightening moves at mechanics
speed, but **loosening** — removing a gate, demoting a protected surface, weakening an approval
rule — needs ADR-grade rationale, because "let's simplify the workflow" is what gate removal
looks like from the inside; ADR-001 set the baseline at that bar. **11.4** The skills execute; this
page carries shape only. **11.5** A deviation is approved in the moment and landed in the
record, and **the same deviation twice is a bug in the process**. **11.6 Batch, don't tweak**,
except mid-incident: workflow changes accumulate and land together at a periodic
**retro** — an ordinary task, titled `Workflow retro: <date>`, that reviews friction since
the last one and records what it decided in its own task record under
`## Decisions made along the way`. That title is the convention a cold session searches
on to find the previous retro. **11.7 In-flight tasks** meet new rules at their next gate.

## 12. The flow in practice

When goal and done-when are statable, `/t-open` makes the **shape judgment**: one task, an
initiative with its clear children, or an initiative whose only child is a design task when
decomposition is unknown. Each child is then workable from its issue and the constitution.

## 13. Open questions

1. **Draft PR timing** — resolved: at the end of `/t-work`.
2. **Human approval on the forge** — awaits §9's platform constraint and Q9.
3. **Stage naming** — the `t-` prefix is a placeholder.
4. **Archive job destination** — where §10's export lands.
5. **Issue templates** — resolved: GitHub issue forms under `.github/ISSUE_TEMPLATE/`,
   specified by `docs/architecture/issue-templates.md`.
6. **Deployment model** — window deploys are assumed (§6.2).
7. **Amendment cadence** — batch at phase boundaries, or every N tasks (§11.6)?
8. **Deadlock rule** — timebox, trial decision, or a per-domain tiebreaker?
9. **Approval policy** — which surfaces need one approval, which the heightened bar.
