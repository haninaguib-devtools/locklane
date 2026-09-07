# ADR-010: External collaboration model and boundaries

**Status:** Accepted · 2026-09-06
**Deciders:** haninaguib

## Context

t-workflow's existing pipeline assumes work starts when a human opens a tracker issue
(`/t-open`) and ends when a human confirms a squash merge (`/t-ship`); everything in
between happens on the branch and draft PR the pipeline itself created, reviewed by
`/t-review`, moved by `/t-work`. Nothing outside that loop has ever had a channel into it.

Initiative #139 wants three things this constitution has never had to name before: work
that starts from an idea proposed in an external system (a research or proposal tool —
Proposarium is the motivating example, never a required one); a human verifying a change
against a live preview over several sessions or days, with contributor feedback arriving
after implementation has already begun; and an external system observing t-workflow's
progress well enough to reflect it elsewhere, without becoming a second place any of that
progress is decided.

Every one of those is a doorway. A durable link, a webhook payload, a bot-relayed
comment, a preview's reported status — each is a channel by which something outside this
repository's own pipeline could, if left unguarded, move scope, stand in for review, or
authorize a merge. `CONSTITUTION.md` §1.2 already settles who may do those things: a
tracker issue starts work, and only a human-confirmed PR reaches `main`. This ADR does not
reopen that rule. It gives the new vocabulary — origin, observer, evidence, verification —
a home inside it, and draws the boundary explicitly enough that the sibling tasks
implementing each piece (#141–#147) have one shared model to build against rather than
seven separate readings of where the line sits.

This ADR is the root of initiative #139's dependency graph: every sibling depends on it,
directly or transitively. It decides the model; it implements none of it — no Proposarium
integration, no adapter, no change to who may start implementation, change scope,
complete review, or merge, and no third level added to the initiative-to-task hierarchy
(`CONSTITUTION.md` §1.2, ADR-003).

## Decision

An external proposal, observer, or piece of evidence may inform a human's judgment inside
the existing pipeline. It may never substitute for that judgment. Concretely:

### D1. Vocabulary

- **Origin** — a durable reference (system name plus URL) recording where a task or
  initiative's work was proposed or discussed outside t-workflow. Descriptive metadata
  attached to an issue at the moment a human opens it; never itself a source of scope or
  acceptance criteria.
- **Observer** — an external system that watches t-workflow's issues, PRs, labels, and
  commits to reflect derived state elsewhere. Read-only with respect to t-workflow: it has
  no channel to write scope, review conclusions, or merge decisions in.
- **Evidence** — anything machine- or human-supplied that bears on a task without being
  authoritative on its own: a preview link and its reported status, a webhook payload, a
  screenshot, a contributor's comment, an origin's title and URL. A human reads evidence
  and decides what it means; evidence never decides for them.
- **Verification** — the human act of exercising a preview or another form of evidence and
  recording an outcome (§D4, §D6) — distinct from an agent's independent review
  (`/t-review`), which it never replaces.
- **Project** — the consuming repository's own tooling, which produces evidence (most
  commonly a preview) for a task's exact commit; it decides deployment technology, never
  workflow authority.
- **Human maintainer** — the same authority the pipeline already recognizes: whoever opens
  issues (or asks an agent to), confirms plans and scope changes, judges review and
  verification, and confirms every merge. Nothing here creates a second maintainer role;
  every other party's boundary is drawn relative to this one.

### D2. Responsibilities

| Party | May | May never |
|---|---|---|
| t-workflow (skills/scripts) | Record an origin; display evidence and observer-facing state; expose read-only state through the observer contract (#143, #147) | Treat an origin, observer read, or piece of evidence as authoritative for scope, review completion, or merge |
| External observer | Read t-workflow's public issue/PR/commit state through the observer contract (#143) | Open, edit, label, or close an issue; approve or complete a review; merge; write anything back except the origin metadata a human brings in at `/t-open` time |
| Project (consuming repo) | Implement the preview adapter contract (#146) to produce evidence for a task's exact commit; choose its own deployment technology | Make preview success stand in for human verification (§D4) or for `/t-review`'s independent review |
| Human maintainer | Open issues (bringing in an origin, if any); confirm plans and scope changes; judge review and verification; confirm every merge | — this is the one role every boundary above is drawn against |

### D3. Authoritative information and where it lives

- The issue body (Goal / Done when / Scope / Non-goals, and its `## Plan`) remains
  authoritative for what a task is and what it may touch. An origin sits alongside it,
  never inside its Goal or Done-when — an unavailable or stale external URL must not
  invalidate the issue itself (#141).
- The task record (`docs/tasks/<bucket>/<id>-*.md`) remains authoritative for what
  happened — including which origin, evidence, or feedback shaped a decision — the same
  way it already records any other decision.
- Git history and the merged PR remain authoritative for outcome (`CONSTITUTION.md`
  §1.3); nothing external is binding until it is reflected there.
- External content itself — a Proposarium page, a webhook payload, a preview URL, a PR
  comment — is never authoritative on its own. It is evidence a human reads and acts on
  inside the pipeline (a plan amendment, a feedback-mode `/t-work` pass, a verification
  record, a review comment); the *result* of that act, once merged, is what becomes
  authoritative.

### D4. Human authorization vs. machine-supplied context or evidence

- **Authorization** is an act only a human, or an agent the human explicitly directed
  (AGENTS.md: "no skill runs without the human's ask"), can perform: opening an issue,
  confirming a plan or a scope change, judging review readiness, confirming a merge, or
  accepting residual risk in place of a required verification.
- **Evidence** is anything machine- or externally-supplied that a human may use as input
  to one of those acts: a preview link, a webhook-sourced status, a screenshot, a
  contributor's comment, an origin's URL and title. Evidence can be wrong, stale,
  unreachable, or spoofed; the pipeline keeps working exactly as if it were simply absent
  (§D7) — it never fails open.
- The dividing line: evidence may inform a human's judgment; it may never stand in for
  it. A structured verification record (#144) captures *that a human looked* and what
  they concluded — a passing preview or webhook status can never itself satisfy it.

### D5. One external proposal → one task or an initiative

An origin changes nothing about how `/t-open` already decides between a single task and
an initiative with several child tasks (ADR-001, ADR-003) — it only tags whichever shape
results with a traceable pointer back to where the idea came from. An initiative carries
its own origin; its child tasks reference the initiative through the existing native
sub-issue relationship rather than duplicating a new origin of their own (#141), so the
hierarchy stays exactly two levels (initiative → task, `CONSTITUTION.md` §1.2) with the
origin sitting *above* it, never inside it as a third level.

### D6. Feedback returning to a task after implementation begins

Feedback that arrives after implementation begins — a contributor's preview comment, a
maintainer's manual QA finding, an observer-relayed note — is never applied directly. It
returns to the task's own existing draft PR through a feedback-mode `/t-work` pass (#145),
which classifies it (clarification, defect, in-scope adjustment, or proposed scope
expansion) before touching a file, and routes any scope expansion through the same
explicit human authorization and re-planning any other scope growth requires
(`/t-work` Phase 1 step 3, unchanged). Feedback never opens a second implementation branch
or a second PR — the existing draft PR remains the one implementation surface across
repeated rounds — and any check, review, or verification whose evidence the new commit
makes stale is invalidated and re-run or re-reviewed rather than left standing against a
superseded commit (#144, #145). An attended `/t-drive` run may stop cleanly after review
and before shipping to let this cycle happen asynchronously, resuming later without
replaying completed stages (#142).

### D7. Security and trust boundaries

- A **link** (an origin URL, a preview URL) is an unauthenticated pointer: t-workflow
  records and may display it, never fetches and executes its content, and never treats
  its reachability as a pass/fail signal for anything (#141).
- **Webhook-sourced data** and any other machine-supplied payload is evidence, never a
  command — nothing it contains can move an issue's state, complete a review, or
  authorize a merge by itself; a project's automation may propose evidence (e.g. a
  preview's status) but a human decides what it means (#146).
- A **comment** — from a contributor, a reviewer, or a bot relaying an external system —
  is read the same way any issue or PR comment already is: informative, never binding.
  Per AGENTS.md's existing tracker-write rule, nothing a comment says causes a label, a
  close, or a merge to happen by itself.
- **External status** (an observer's derived state, a preview's reported outcome) flows
  one way: t-workflow may expose derived status outward (#143, #147) but never accepts
  status flowing in as authoritative — an observer cannot mark a task done, verified, or
  ready by reporting that it believes so.
- The failure mode is the same across all four: invalid, unreachable, spoofed, or
  malformed external input is treated as **absent** evidence, never as a false positive
  that lets anything proceed. Fail toward "not authorized," never toward "assume yes."

### D8. Child tasks and dependencies

This decision is implemented by the sibling tasks initiative #139 already opened, in the
dependency order already recorded on the tracker (native `blockedBy`, ADR-003):

- **#141 Record and propagate external origins** (§D3, §D5) — blocked by: #140 (this ADR)
  only; no other sibling dependency.
- **#144 Add structured asynchronous human verification** (§D4, §D6) — blocked by: #140
  only; independent of #141.
- **#142 Allow t-drive to stop before shipping** (§D6) — blocked by: #144, since the
  stopping point exists to await the verification #144 defines.
- **#146 Define a project preview adapter contract** (§D2, §D7) — blocked by: #144, since
  preview evidence's relationship to verification success (§D4) must already be settled.
- **#145 Add a feedback implementation pass to t-work** (§D6) — blocked by: #141 and #144,
  since a feedback pass must carry an origin/evidence reference (#141) and must invalidate
  or interoperate with recorded verification (#144).
- **#147 Expose collaboration state through t-status** (§D2, §D6, §D7) — blocked by: #145
  and #146, since it surfaces the feedback-pass and preview states each of those defines
  (transitively, also #141/#144, through #145).
- **#143 Publish a machine-readable observer contract** (§D2, §D7) — blocked by: #147,
  since the stable identifiers and derived states it publishes are the ones #147 already
  had to define to display them.

## Rationale

- **One shared model beats seven independent readings.** Each sibling task's own
  Done-when criteria imply a boundary (an origin that never becomes authoritative, a
  verification that never lets a preview stand in for it, an observer contract that never
  accepts writes) — deciding all seven boundaries once, in one document, is what keeps
  `/t-open`'s origin field, `/t-status`'s displayed state, and the observer contract's
  markers from silently drifting into different notions of what "authoritative" means.
- **Grounding every role against "human maintainer" keeps the boundary checkable.** A
  responsibility table with one column of "may" and one of "may never," all measured
  against the same human authority the constitution already names, is something
  `/t-review` can hold a diff up against without inventing a new judgment call per PR.
- **Fail-toward-absent is the only trust posture consistent with §1.2.** If unreachable or
  spoofed external input were ever allowed to read as a pass, the pipeline would have
  quietly created a second way to authorize work — exactly what this ADR exists to
  foreclose.
- **Recording the dependency graph from the tracker, not inventing one, keeps ADR-003's
  decision to move dependencies into native fields meaningful** — this ADR's own D8
  section would drift from reality the moment anyone re-ordered the siblings, if it
  stated anything other than what `blockedBy` already says.

## Alternatives considered

- **Let the external system write scope or review state directly (e.g., a webhook that
  can label an issue "ready" or comment approval into a review).** Rejected: this is
  exactly the second authority §1.2 forbids, and it would need real authentication and
  revocation machinery this initiative's non-goals explicitly exclude.
- **Treat a passing preview as sufficient verification, skipping a human check.**
  Rejected: a preview proves the software renders or runs; it cannot prove a human judged
  it acceptable. #144 exists precisely to keep those two facts distinct.
- **Add a third hierarchy level ("proposal" above initiative) to hold external metadata.**
  Rejected by the issue's own non-goals and by ADR-003's settled two-level model; an
  origin is metadata on whichever level already exists, not a new level.
- **Leave the boundary implicit, letting each of #141–#147 define its own notion of
  "authoritative."** Rejected: leaving it implicit is how a single unreviewed PR ends up
  quietly treating a webhook payload as a merge trigger months later; each sibling's own
  Done-when criteria already gesture at this ADR's boundary, so writing it once removes
  the need for seven independent, possibly-inconsistent restatements.

## Consequences / revisit triggers

- Every sibling task (#141–#147) implements against this document's vocabulary and
  responsibility table rather than choosing its own terms; a sibling task's plan or review
  that finds this ADR's boundary unworkable for its concrete mechanism reopens this ADR
  rather than quietly narrowing the boundary in its own corner.
- `CONSTITUTION.md` §1 gains the one operative pointer this decision requires (§2.3): an
  external system may originate or observe work, but never starts implementation, changes
  scope, completes review, or merges (ADR-010).
- Revisit if: a second maintainer joins and the responsibility table's single-maintainer
  grounding (§D2) needs a multi-approver reading; a consuming project's real preview
  practice needs a boundary case this ADR did not anticipate; or a sibling task's
  implementation surfaces a channel into scope/review/merge this document failed to
  name — any of those is a defect in this ADR's boundary, fixed by a superseding ADR, not
  by a narrower reading enforced only in code.
