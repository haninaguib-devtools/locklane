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
- none
