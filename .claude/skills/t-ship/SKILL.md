---
name: t-ship
description: Ship a task — mark its draft PR ready, obtain the human's confirmation, and squash-merge with a self-contained commit written from the record. The only path to the project's trunk. Use when a task is finished and ready to merge.
---

# Ship a task

Resolve every `tracker:*` / `forge:*` operation named below via
`docs/adapters/TRACKER.md` and `docs/adapters/FORGE.md` (GitHub by default). The merge
stage: the human's confirmation is the strategic read, and, when no cold review ran,
the only read the change gets before the trunk.

Read `AGENTS.md` and `CONSTITUTION.md` first — unless this ship is `/t-drive` chaining
this stage in the same session whose Phase 0 already read them for the whole run, in
which case that read already covers this one. A standalone invocation, with no driving
session, always reads both itself. Resolve the project's trunk name once, up front
(`.t-workflow/scripts/trunk-ref.sh`, `<trunk>` below), and use it everywhere a step
below would otherwise name `main` literally.

## Preconditions

Every step below names `<pr>`. **Resolve it from the task id first** with
`forge:pr-find-by-task <id>`, matching head branch `wip/<id>-*`. Exactly one open PR →
that is `<pr>`. None → the task hasn't reached `/t-work`'s draft-PR step, stop and say
so. More than one → stop, report every candidate. Already merged or closed → the task
has already left the pipeline, say which and stop.

0. **Protected surfaces, from the diff.** Get the PR's own file list
   (`forge:pr-files <pr>`) and pipe it into `bash .t-workflow/scripts/protected-paths.sh --stdin`
   (`CONSTITUTION.md` §3 in executable form) — never a label, never what the issue
   predicted — to decide preconditions 1 and 2. Read the exit code exactly: **0** =
   protected (paths echoed), **1** = checked, none protected, **2** = nothing was
   checked — a broken pipeline, not a clean PR; stop rather than skip the plan and
   review gates on a diff nobody looked at.

1. **Plan.** A protected path with no `## Plan` section on the issue means the work grew
   onto a protected surface after `/t-work` judged it wouldn't (`CONSTITUTION.md` §3).
   Stop and name `/t-plan <id>`; the task then returns through `/t-review`.

2. **Review.** Required when the PR touches a protected surface; missing → stop and name
   `/t-review <id>`. If a review exists, fetch it once here with `forge:pr-reviews <pr>`
   and hold the result — **precondition 6 below reuses this same fetch**, never
   re-querying it. Its latest verdict governs regardless: `not-ready` → `/t-work <id>`
   in fix mode. No review and no protected path → continue, saying out loud that none
   ran.

   On a protected surface, feed the same fetched reviews and the PR's changed paths
   (precondition 0's own `forge:pr-files <pr>`, already computed) into
   `.t-workflow/scripts/check-review-gate.sh <head-commit-time> <reviews-file>`
   (`<head-commit-time>` from `forge:pr-view <pr>`'s head commit), rather than checking
   the `isolation:` line and staleness by hand. Exit 1 → stop, send it back to
   `/t-review <id>`, quoting the script's own reason (a missing or `same session`
   isolation line, or a review older than the head commit).
3. The branch merges cleanly against current `origin/<trunk>` (`forge:pr-view <pr>`). A
   conflict means another task landed first; resolving it goes back through `/t-work`.
   Mergeability is computed asynchronously and often returns **unknown** at first: wait
   a few seconds and retry, up to about three times; still unknown → carry
   `mergeability: unknown` into the gate's evidence rather than asserting a clean merge
   that was never confirmed.
4. **What is about to merge is what was built.** The PR carries only pushed commits;
   compare the local branch tip (`git rev-parse HEAD`, on the task branch) with the
   PR's head (`forge:pr-view <pr>`). Mismatch → stop, say which commits are unpushed,
   push them (re-runs CI and, on a protected surface, invalidates the review per
   precondition 2). Off the task branch → say the comparison could not be made.
5. The task record is in the diff and current, deviations included — or, for a driven
   initiative's aggregate PR (`/t-drive`, ADR-004, branch `wip/<id>-integration`), every
   included child's own record is in the diff, each already current from its own merge
   into the integration branch.
6. **Pending human checks**, when a review exists: read it from **precondition 2's own
   `forge:pr-reviews <pr>` fetch** — never a second call for the same PR. Its
   `## Pending human checks` section lists judgments no command can settle. **Checks
   listed** → carry them into the confirmation, non-blocking, acknowledged by
   confirming. **Reads `none`** → say so, carry `none` as the evidence value. **A
   review with no such section** → **unknown, never `none`**: stop before the gate and
   ask the human to name the checks from the plan or confirm there are none. No review
   at all → the evidence value is `no review ran`.

   **CI is not a precondition here** (amended #113): the pipeline now skips CI on a
   draft PR (`.github/workflows/ci.yml`, `.github/workflows/review-gate.yml`) and starts
   it at `ready_for_review`, so a CI read taken before this PR is marked ready would see
   "nothing ran" and misreport it as green. CI-green moves into the Procedure, evaluated
   only after step 1 marks the PR ready and actually starts it — see Procedure step 2.

## Procedure

1. `forge:pr-ready <pr>` — the draft becomes ready. This is also what starts CI (#113):
   `ci.yml` and `review-gate.yml` both skip a draft PR and trigger on
   `ready_for_review`/the readiness transition, so this step is the first moment CI for
   this exact head commit can even exist.
2. **CI-green, watched attended** (#113, amending ADR-007's *unattended* one-look rule
   for the structurally similar case in `/t-drive` Solo step 6 — see
   [ADR-008](../../../docs/adr/008-t-ship-attended-ci-watch.md) for why the two differ).
   Read the PR's checks (`forge:pr-checks <pr>`).
   - **No CI configured** → acceptable, said out loud, continue to step 3.
   - **Every check already concluded** (green or red) → continue immediately, no wait.
   - **Any check still queued or in progress** → a human is present at this gate and is
     the bound (unlike `/t-drive`'s unattended solo look), so **watch**: re-read
     `forge:pr-checks <pr>` on a short interval until every check concludes, or the
     session is interrupted. **Interrupted mid-watch** → stop here, report which checks
     were still pending, and say that the PR stays ready — a later `/t-ship <id>`
     re-enters this same step and finds them concluded (or still watches, if they
     somehow are not). Never a fixed timeout to calibrate and never a give-up: the human
     watching is what bounds this, exactly as ADR-007's rationale requires for its own
     one-look design, just attended here instead of unattended.
   - **Every check concludes green** → continue to step 3.
   - **Any check concludes red** → stop. **Put the PR back into draft**
     (`forge:pr-draft <pr>`) — step 1 marked it ready in expectation of CI that has now
     failed, mirroring step 3's own `abort` handling below — report which check failed
     and where to read why, and name `/t-work <id>` (fix mode) as the next command. Never
     reach the merge-confirmation gate on a red or unconcluded check.
3. **Branch-protection contexts, only if this PR renames them** (#113). Check whether
   this PR's diff changes the required-status-check `contexts`
   `.t-workflow/scripts/github-bootstrap.sh` asserts. **Not applicable** (the common
   case) → continue to step 4. **Applicable** → this PR cannot merge under the *current*
   live protection, self-referentially: whatever the old contexts named, this PR's own
   CI no longer produces them (that is the diff), so the old required checks would sit
   at "expected" forever and block the merge button — the exact #94/#109 failure,
   self-inflicted on this PR unless the live setting moves before the merge is
   attempted, not after. Confirm the new context name(s) already have a real run to
   point at — **this PR's own head sha**, not the trunk (the merge hasn't happened yet):
   `gh api repos/<owner>/<repo>/commits/<head-sha>/check-runs`, expecting `checks`
   (from step 1–2 above) and `cold-review` (from precondition 2's review, which must
   already exist on a protected surface) to both appear. Missing either → stop, name
   what's missing; do not flip protection against a context nobody has produced.
   **Do not execute the flip yet** — it happens only after the human's confirmation
   below, folded into the same gate rather than a second one
   (`docs/architecture/confirmation-gates.md`: one gate per turn), since the flip is
   what makes the merge this gate authorizes actually possible.
4. **Stop and ask the human to confirm the merge**, showing the PR URL
   (`forge:pr-view <pr>`) and a one-paragraph what-and-why in plain prose per AGENTS.md
   §Communication, then **the pending human checks from precondition 6** — the last
   moment they can be raised. If approval rules are configured on the repo, they must
   also approve on the forge (`forge:pr-approval`). Do not merge on silence.

   End the message with the gate, per `docs/architecture/confirmation-gates.md`: a plain
   question (or the environment's native question mechanism), last thing in the
   message —

   - evidence: review `<verdict, or 'no review ran'>` · CI `<state, and which checks — or
     'no CI configured'>` · diff `<files/size summary>` · human checks `<the pending
     checks, or none>` · branch protection `<'no change needed', or 'will update
     required checks from <old list> to <new list> — required for this merge to
     succeed', from step 3>`
   - question: "Merge PR #<pr> into <trunk>?"
   - options: `confirm` (human checks judged) / `abort` (do not merge)

   **On `abort`, put the PR back into draft** (`forge:pr-draft <pr>`) before reporting —
   step 1 marked it ready in expectation of a merge that did not happen; nothing else is
   touched, including branch protection — step 3 only checked, it never executed.
   **Outstanding checks are acknowledged, not blocking** — confirming *is* the
   acknowledgement; a human who wants a check settled first answers `abort`.
5. **On confirmation:** if step 3 found the flip applicable, execute it *now*, before
   attempting the merge — this is what the confirmation just given authorizes, alongside
   the merge itself. Flip the live setting directly to the exact list
   `.t-workflow/scripts/github-bootstrap.sh` asserts, then read the same endpoint back
   and confirm it matches:

   ```bash
   echo '{"strict": false, "contexts": [<the new list>]}' |
     gh api "repos/<owner>/<repo>/branches/<trunk>/protection/required_status_checks" \
       -X PATCH --input -
   gh api "repos/<owner>/<repo>/branches/<trunk>/protection/required_status_checks" \
     --jq .contexts
   ```

   **Never run `github-bootstrap.sh` here.** Its required-checks gating looks for the
   new context on the trunk, which cannot exist until this very merge lands — pre-merge
   it takes its "CI has not run on `<trunk>` yet" branch instead of asserting the new
   list (#113's own ship tripped exactly this). The script is a post-merge true-up only:
   after the merge, once the trunk's first CI run of the new context concludes, re-run
   it and confirm it reports the same list this step just set. Not applicable → nothing
   to do here, continue.

   Then squash-merge with a **self-contained commit** written from the record, via
   `forge:pr-merge <pr>` — **re-read `docs/adapters/FORGE.md`'s `forge:pr-merge` row
   first and check the exact command against it.** The subject overrides the forge's
   default entirely, so append the PR reference explicitly.

   **Ordinary task (the common case, unchanged):**

   ```
   subject: [<id>] <issue title> (#<pr>)
   body:    <goal — one line>

            Non-goals: <from the record's Explicitly not>
            Outcome: <what shipped; notable decisions and deviations from the record>

            Task: #<id> — docs/tasks/<bucket>/<id>-<slug>.md
   ```

   **A driven initiative's aggregate PR** (`/t-drive`, ADR-004 Decision 3 — branch
   `wip/<id>-integration`, `<id>` the initiative's own id): the body's goal/non-goals
   come from the initiative issue itself, which carries no record of its own; one
   `Task: #<id>` line per **included** child, never a blended paragraph, each naming
   that child's own record — read the included/excluded lists straight from the PR's own
   body, which `/t-drive` Phase 3 step 1 already wrote them into, rather than
   re-deriving them:

   ```
   subject: [<id>] <initiative title> (#<pr>)
   body:    <goal — one line, from the initiative issue>

            Non-goals: <from the initiative issue's own Non-goals>
            Outcome: <children included, by number; children excluded, by number and
                     why — from the PR body's own lists>

            Task: #<child-1-id> — docs/tasks/<bucket>/<child-1-id>-<slug>.md
            Task: #<child-2-id> — docs/tasks/<bucket>/<child-2-id>-<slug>.md
            …
   ```

   If the tracker auto-closes on merge (`tracker:auto-close-on-merge`), the PR body's
   phrase closes the issue now — one such phrase per `Task:` line, so a driven PR closes
   every included child, never only the initiative; otherwise close each explicitly with
   `tracker:close-done` (as completed) — never `tracker:close`, which closes as
   not-planned. A child's own PR into the integration branch (`/t-drive` Phase 2 step 6)
   never carries this phrase — merging into a non-default branch does not trigger the
   forge's auto-close, and a child is not done until its work reaches the trunk through
   this PR.
6. `git fetch --prune` (deleted `wip/` branches otherwise linger as stale
   `origin/wip/*` refs), then **at most, fast-forward the trunk this checkout happens to
   be sitting on** (ADR-002) — merging leaves the task's worktree, local branch, and
   this checkout untouched, left alone permanently (ADR-005), never a side effect of
   shipping: `git rev-parse --abbrev-ref HEAD`, then **on `<trunk>`**: `git merge --ff-only
   origin/<trunk>`; **on any other branch**, including the task branch: leave it exactly
   where it is — the normal outcome now that shipping runs from anywhere.
7. A `tracker:view <id>` `parent` field names a tracking issue whose `subIssuesSummary`
   the task's close above already updated — nothing to write here.
8. Report the merge commit hash, whether a cold review ran, whether branch protection
   was updated (step 3/5), and whether this checkout's
   trunk was fast-forwarded. **If `subIssuesSummary` (`tracker:view <parent-id>`) now
   shows every child closed**, ask whether to close the initiative too — never
   automatic. For a driven run that just shipped, `<parent-id>` is `<id>` itself (the
   initiative just driven): every included child closed above, and an excluded child, if
   any, stays open — so `subIssuesSummary` reads all-closed only when nothing was
   excluded; the same yes/no question applies either way. Yes → close as completed
   (`tracker:close-done`), comment naming the
   delivering tasks. No → leave it open and say what remains.

## Rules

- Never merge without the human's explicit confirmation in this conversation.
- Never force-push; never push the trunk branch directly.
- Do not edit the change while shipping — a defect noticed here is a new finding or
  issue, not a drive-by fix.
