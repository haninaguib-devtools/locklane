# ADR-011: A project is private to its owner, with no administrator exemption

**Status:** accepted (2026-08-30). Supersedes the administrator exemption in
[ADR-007](007-multi-user-tenancy-and-authorization.md) Decisions 1 and 6; the rest of
ADR-007 stands unchanged.

## Context

ADR-007 made `owner_user_id` the isolation boundary between accounts, and then carved an
administrator out of it twice. Decision 1 scoped every project read and mutation to the
owner but let an administrator through; Decision 6 derived worktree and console session
visibility from the owning project's owner and added "admins can see and attach to any
session". Those carve-outs were implemented literally: `ProjectController.list` hands an
administrator `findAll()` rather than their own projects, `ProjectController.findAuthorized`
returns any project to an administrator regardless of ownership, and
`WorktreeSessionAuthorization.isVisibleTo` returns `true` for an administrator before it
looks at ownership at all.

The practical effect is that the first account ever bootstrapped — always an administrator
(ADR-007 Decision 3) — can list, open, retry, delete, and set a GitHub token on every other
account's projects, and can attach to any account's live terminal. Attaching to a terminal
is not a read-only capability: it is a shell in someone else's checkout, with that project's
decrypted GitHub token in its environment.

Locklane's deployment shape is a home server shared by a handful of people who are peers,
not an organization with an operations team the members have agreed to be overseen by. The
person who happened to run the installer first is an administrator by accident of ordering,
and nothing about that ordering implies the others consented to being visible to them.

The owner reconsidered the exemption and decided to reverse it. This ADR records that
decision. It is not a deliberation, and it does not reopen the choice — the deliberation
issue opened for that purpose was cancelled for exactly this reason.

## Decision

1. **A project is visible and operable only to its owner, whatever the caller's role.**
   `ProjectController.list` returns the caller's own projects and nothing else.
   `ProjectController.findAuthorized` authorizes on ownership alone, with no role
   exemption; a project belonging to another account resolves to empty and is reported as
   404, indistinguishable from a project that does not exist. This replaces the
   administrator half of ADR-007 Decision 1; that decision's substance — `owner_user_id`
   as the boundary, enforced in the application layer — is otherwise untouched.
2. **Worktree and console session visibility derives from the owning project's owner, and
   from nothing else.** `WorktreeSessionAuthorization` grants an administrator no access to
   another account's sessions. This replaces the parenthetical administrator clause in
   ADR-007 Decision 6; the rest of that decision — visibility derived from the project
   rather than from first-attach, `owner_username` as a denormalized record — stands.
3. **Administrators keep account management and gain nothing else.** ADR-007 Decision 3
   (the first bootstrapped account is an administrator; no self-registration) and the
   admin-only user-management surface are unchanged. The role stops being a way around
   project ownership; it does not stop existing.
4. **No operator route to another account's project is built, and the resulting
   limitation is accepted.** Nobody can clean up, transfer, or debug an account's project.
   The only route to a project whose owner is gone or stuck is deleting the owning account,
   which cascade-deletes that account's projects (ADR-007 Decision 4). Building a recovery
   path is separate work, not deferred work implied by this decision.

## Rationale

- **The exemption's cost is concrete and its benefit is hypothetical.** The cost is that
  every account's source checkouts, project tokens, and live shells are readable by one
  other account, permanently, with no audit trail and no consent step. The benefit was an
  operator convenience nobody had yet needed: no support workflow, no runbook, and no
  request for one existed.
- **A terminal attach is not an administrative read.** ADR-007 grouped session visibility
  with project visibility, which made "admins can see and attach to any session" look like
  the same kind of permission as listing rows. It is not — it is interactive code execution
  in another person's working tree, with their credentials in the environment. Grouped
  correctly, it does not survive the trade above.
- **Deployment shape, not company shape.** ADR-007's own rationale notes that a home-server
  deployment has no OS-level tenant boundary to lean on, which is why the check lives in
  Java. The same observation cuts against the exemption: with no OS boundary underneath,
  the Java check is the entire boundary, and a role that bypasses it is the entire absence
  of one.
- **A single rule is auditable; an exempted rule is not.** With the exemption gone,
  `grep -rn "Role.ADMIN" engine/src/main/java` is a complete audit of where the role can
  affect authorization — the answer is `UserBootstrapper`, assigning it, and nowhere else.
  While an exemption exists, every authorization site has to be read individually to know
  whether it honors ownership.
- **Reversing this later is cheap; unwinding a disclosure is not.** Restoring an operator
  path is additive work against a codebase whose boundary is by then uniform. Access
  already taken cannot be withdrawn.

## Alternatives considered

- **Keep ADR-007 as ratified.** Rejected: it is exactly the arrangement the owner
  reconsidered, and the concrete cost above is paid continuously by every non-administrator
  account.
- **A narrowed administrator: deletion and recovery powers only, no reading or attaching.**
  Considered seriously and rejected. It preserves the orphaned-project escape hatch, which
  is the one real benefit, but a delete-only power is close to useless without a look-first
  power, and every proposed shape of "look first" reintroduced reading someone else's
  project. It also leaves two authorization models in the code — ownership for most
  operations, ownership-or-role for a few — which is the auditability cost above at a
  smaller scale but not at zero.
- **Owner-granted access: a project owner can share a project with another account.** Not
  rejected on merit — it simply is not this decision. It is additive, needs a sharing model
  and a UI, and would be its own ADR. Nothing here forecloses it.
- **Log administrator access instead of forbidding it.** Rejected: an audit log deters
  where there is an authority to answer to, and a peer home server has none. It also would
  have to be built, which makes it strictly more work than removing the exemption.
- **Make it configurable.** Rejected: a privacy boundary that a deployment can quietly turn
  off is not one an account can rely on, and the setting would need its own authorization
  story — who may flip it, and why that is not the same problem again.

## Consequences / revisit triggers

- Three call sites change and lose their role branch: `ProjectController.list`,
  `ProjectController.findAuthorized`, and `WorktreeSessionAuthorization.isVisibleTo`.
- Tests that used an administrator account to reach another account's project or session
  are inverted into denial tests. Tests that merely logged in as an administrator so a
  session id would pass the visibility check are fixed by giving the test account real
  ownership of the session's project — never by keeping an exemption alive
  (`CONSTITUTION.md` §1.5).
- A project whose owner is gone or stuck is unreachable. Deleting the owning account is the
  only remedy, and it destroys that account's other projects too.
- **Revisit if** a support workflow actually arises — a real request to recover a real
  orphaned project, more than once — in which case the owner-granted sharing model above is
  the first alternative to reach for, not a restored role exemption. **Revisit also if**
  Locklane is ever deployed somewhere with a genuine operations role and an agreement that
  members are subject to it, since this decision's rationale rests on the absence of both.
