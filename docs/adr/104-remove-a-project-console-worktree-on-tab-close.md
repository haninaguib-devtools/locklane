# ADR-104: Remove a project-console worktree on tab close, with the sweep as backstop — a second, distinct ADR-102 carve-out

**Status:** Accepted · 2026-08-29
**Deciders:** project owner *(solo phase; queued for review alongside ADR-001–009 if a
second maintainer joins — workflow §13 Q9.)*

## Context

ADR-102 carved a narrow, guarded exception out of ADR-005's "left alone permanently,
removed by hand only": a console-created, per-issue worktree is removed automatically
once its issue is closed, its git status is clean, and no live session is attached —
because a person pressing "Console" against an issue has no reason to know a worktree
now exists on disk, let alone that it should eventually be removed by hand. ADR-102's
own revisit trigger 3 named exactly the gap this ADR closes: "a new worktree-creation
path this guard was not written for appears — e.g. a project console getting its own
worktree (#314) — and needs its own eligibility rule, distinct from the per-issue one
this ADR covers."

A project console (#139, #314) has no issue of its own — it exists for pre-issue
discussion and `/t-open`, and since #338 its worktree's HEAD is created detached at
`origin/main` rather than on a freshly minted branch, precisely because a console
almost never commits. ADR-102's guard cannot apply here at all: its first condition is
"the issue is closed," and a project console has none to check. Left uncovered, every
project console ever opened leaves its worktree on disk forever, with even less of a
human touchpoint than ADR-102's own target — closing a console tab is a single click
that gives no indication a worktree still needs cleaning up, and a console never
prompts anyone to look at its issue's state because it has none.

Unlike a per-issue worktree, a project console's worktree is not safe to gate on
"issue closed" — there is no such signal — but it carries a risk ADR-102's guard never
had to consider: the worktree's HEAD can be detached, so any commit made on it lives
nowhere else. `git worktree remove` deletes the worktree's own reflog along with it;
a commit made there and nowhere else becomes unrecoverable the moment that happens.
ADR-102's three-part guard (issue closed, clean, unattached) has no analog for "does
this directory hold the only copy of some commit" because a per-issue worktree always
sits on a named branch, which itself survives the worktree's removal (a separate
concern ADR-103 addresses for that branch specifically). A detached-HEAD worktree has
no branch to preserve anything — the guard for this path must itself confirm nothing
would be lost.

## Decision

A project-console worktree is removed automatically — on its tab's explicit close, and
by `WorktreeCleanupSweeper`'s periodic sweep as a backstop — once every one of these
holds, checked fresh at the moment of removal, exactly the same all-or-nothing shape
ADR-102 already established for the per-issue case:

1. the session has ended — no live session's working directory is inside it
   (`SessionRegistry#hasLiveSessionIn`, the same check ADR-102 already uses; at tab
   close this is satisfied by construction once that tab's own session has been ended);
2. HEAD is detached — not sitting on any checked-out branch. A worktree with a branch
   checked out is left alone unconditionally: it means the console outgrew scratch use
   and became real work, the same posture ADR-005 already takes toward a developer's
   own deliberately-created branch;
3. `git status --porcelain` reports nothing outstanding — the same cleanliness check
   ADR-102 already uses, reused verbatim;
4. HEAD's commit is an ancestor of (freshly fetched) `origin/main` — the guard ADR-102
   never needed, because a per-issue worktree's branch survives its worktree's removal
   and a detached one has nothing else to survive in. A commit made on detached HEAD
   and not yet reachable from `origin/main` would be permanently lost the moment the
   worktree (and its reflog) is deleted; failing this check keeps the worktree around
   exactly so that commit is not silently destroyed.

A worktree failing any one of these is kept, never force-removed, and appears in the
project's worktree list with a human-readable reason for whichever check failed
first — the same `removalRefusalReason` pattern ADR-102's own per-issue guard already
established, extended to cover this second, distinct eligibility rule rather than
folding it into the first.

This is a second, independent carve-out from ADR-005, sitting alongside ADR-102's
rather than replacing or merging into it: the two guards check different things for
different reasons (issue state versus git ancestry) and apply to different
worktree-creation paths (per-issue versus project-console). A per-issue worktree is
still governed by ADR-102 and ADR-103 exactly as written; nothing here changes either.

## Rationale

- **Same absence-of-human-touchpoint argument ADR-102 already made, for a path ADR-102
  itself flagged as uncovered.** A project console's worktree has, if anything, less
  of a human touchpoint than a per-issue one: closing a tab is a single click with no
  issue-state signal attached to it at all. ADR-102's own revisit trigger 3 anticipated
  this exact gap by name.
- **The ancestor-of-`origin/main` check is the one genuinely new piece, and it exists
  because detached HEAD has no other safety net.** A per-issue worktree's branch
  outlives the worktree (modulo ADR-103's own separate, git-merge-gated deletion); a
  detached worktree has nothing else holding its commits once the worktree's reflog is
  gone. Refusing removal whenever a commit is not yet reachable from `origin/main` is
  the minimum check that makes automatic removal as safe here as ADR-102's guard
  already is for the per-issue case — a fully mechanical check (`git merge-base
  --is-ancestor`), not a judgment call, matching `CONSTITUTION.md` §1.5's requirement
  that guardrails are never weakened to make automation simpler.
- **The branch-checked-out guard is a deliberate, permanent exclusion, not an
  oversight to fix later.** A project console whose worktree has grown a real branch
  has, by that fact alone, outgrown scratch use — exactly the shape of work ADR-005
  already says is left alone permanently, removed by hand only if it is ever actually
  in the way. This ADR does not attempt to also judge whether that branch's work is
  "done"; it simply declines to touch a worktree that has stopped looking like
  disposable scratch.
- **Reusing `removalRefusalReason`'s shape rather than inventing a second pattern.**
  The project page already shows a per-issue worktree's refusal reason inline; a
  project-console worktree failing this ADR's guard is presented the same way, so a
  person reading the page sees one consistent shape for "why is this still here,"
  regardless of which of the two guards applied.
- **The sweep is the backstop for exactly the cases the synchronous close-time attempt
  cannot catch** — an engine restart or crash between console open and tab close, or a
  worktree left dirty and then forgotten rather than ever explicitly closed — the same
  role ADR-102's sweep already plays for the per-issue path, extended to this second
  eligibility rule rather than duplicated as a separate mechanism.

## Alternatives considered

- **Fold this into ADR-102's existing guard as a fourth, universal condition** —
  rejected: ADR-102's first condition ("issue closed") is meaningless for a worktree
  with no issue, and this ADR's ancestor-of-`origin/main` condition is meaningless for
  a per-issue worktree whose branch already survives its removal. The two guards check
  genuinely different things for genuinely different worktree shapes; merging them
  would make either guard's failure message misleading for the other's worktree kind.
- **Skip the ancestor-of-`origin/main` check and rely on cleanliness alone** —
  rejected outright: `git status --porcelain` says nothing about a commit already made
  and not yet pushed anywhere else. A clean, detached worktree with an unpushed commit
  would look identical to a truly empty one to that check alone; removing it would
  destroy the only copy of that commit the moment its reflog goes with it.
- **Also remove a worktree with a branch checked out, once that branch itself is
  merged or deleted** — rejected as broader than this task and outside what a project
  console is for: a console that has grown a real branch is no longer disposable
  scratch by definition, and deciding what happens to that branch is squarely
  ADR-005's manual-cleanup territory, the same as any other deliberately created
  branch.
- **A single merged eligibility rule keyed on "no issue" vs. "has an issue" inside one
  method** — rejected as a false economy: the two rules already read cleanly as two
  short, independent checks: writing one method with a branch in the middle for "which
  kind of worktree is this" would only make each rule harder to read in isolation, for
  no reuse actually gained beyond the parts (cleanliness, live-session check) that
  already are shared as-is.

## Consequences / revisit triggers

Accepted knowingly: a project console left dirty, or one that grew a real branch, sits
on disk exactly as long as ADR-102's own accepted consequence already allows for the
per-issue case — the sweep only ever acts when already safe, it never nags, and a
branch-bearing console is never swept at all, by design, however old it gets.

Any of these reopens this decision, as a new ADR:

1. **This guard ever allows removal of a worktree that turns out to have held
   unrecoverable work** — the single most serious trigger, the same posture ADR-102
   and ADR-103 both take toward their own guard's first condition; suspend the
   close-time and sweep removal for project consoles and re-derive the guard rather
   than patching around a near-miss.
2. **A project-console worktree ever needs its own branch preserved past its
   worktree's removal**, mirroring ADR-103 for the per-issue path — not needed today
   because this guard already refuses to remove any worktree with a branch checked
   out at all.
3. **A third worktree-creation path appears that this guard was not written for** —
   the same shape of gap ADR-102's own trigger 3 named for this one.
