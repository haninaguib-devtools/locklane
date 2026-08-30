# 394 — Make a user's projects private to that user
Issue: #394

## Asked
A project a person creates should be visible only to them. Today the first account ever
bootstrapped — always an administrator — sees every other account's projects, can open,
retry, delete and set a GitHub token on any of them, and can attach to any project's
worktree or console session. ADR-007 (Decisions 1 and 6) granted that exemption
deliberately; the owner has decided to reverse it, fully: an administrator gets no more
access to another account's projects and sessions than any other account does.

Because ADR-007 is ratified and `docs/adr/` is append-only, the reversal carries a new ADR
in the same PR, superseding those two decisions by name and recording the accepted cost —
nobody can clean up, transfer, or debug another account's project; deleting the owning
account, which cascade-deletes its projects, stays the only route.

## Done when
- A new numbered ADR under `docs/adr/` states the fully-private model, supersedes the
  administrator exemption in ADR-007 Decisions 1 and 6 by name, and records the
  orphaned-project limitation as accepted. ADR-007 itself left unedited.
- The operative one-line rule lands in `CONSTITUTION.md` §4 with a pointer to the new ADR.
- `ProjectController.list` returns only the caller's own projects, whatever their role.
- `ProjectController.findAuthorized` authorizes on ownership alone.
- `WorktreeSessionAuthorization` grants an administrator no access to another account's
  worktree or console sessions.
- Tests cover an administrator being denied on another account's project (list, open,
  retry, delete, set token) and on its worktree and console sessions.
- `grep -rn "Role.ADMIN" engine/src/main/java` shows no authorization bypass left.
- `./mvnw -B test` and `./.t-workflow/scripts/consistency-check.sh` pass.

## Explicitly not
- Changing account creation or the first-bootstrapped-account-is-admin rule (ADR-007
  Decision 3) — administrators still exist and still manage accounts.
- Changing user deletion's cascade behaviour (ADR-007 Decision 4).
- Building any operator route to an orphaned project. The limitation is accepted, not fixed.
- Revisiting ADR-007's other decisions.
- Editing ADR-007 itself (`CONSTITUTION.md` §2.1, append-only).
- Any client change: the Angular admin screens are account management, and the project
  list needs none because the API simply returns fewer rows.

## Decisions made along the way
- none

## Deviations / notes
- **An out-of-scope edit was made and then withdrawn.** The first implementation pass also
  corrected a code comment in
  `engine/src/main/java/dev/locklane/engine/ws/TerminalWebSocketHandler.java`, which still
  describes the attach check as reading "owner_user_id (or admin status)". That file is not
  in the plan's Allowed paths, and the cold review on PR #406 raised it as a high finding —
  correctly: an unlisted path is out of scope whether or not the change is harmless, and the
  first version of this record said "none" here, which made it a silent deviation as well.
  The fix pass reverted the file to its state on `main`. Nothing else in the diff was
  touched, and the checks were re-run.
- **A stale comment is therefore left in place, knowingly.** That comment now describes an
  authorization rule this task removed. It is prose, not behaviour — no code reads it — so it
  is left for the human rather than fixed out of scope. It is the same defect as the review's
  medium finding on `UserRecord.java:30` ("ADMIN can manage other accounts and every
  project"), and the two belong in one follow-up issue: *"Correct the comments that still
  describe the withdrawn administrator exemption"*, covering `TerminalWebSocketHandler` and
  `UserRecord`. Proposed here for the human to open; no issue was created by this task.
- The review's low finding — ADR-011's status line reading `accepted (2026-08-30)` where
  `docs/adr/000-template.md` and ADR-010 use `Accepted · <date>` — is inside scope but is a
  low finding, which a fix pass addresses only when the human asks by number. Left as-is,
  reported.
