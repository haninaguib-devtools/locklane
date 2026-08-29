# ADR-007: Multi-user tenancy and authorization model

**Status:** Accepted · 2026-08-29
**Deciders:** project owner *(solo phase; see ADR-001's Deciders note — the same
caveat applies here)*

## Context

Today the engine has exactly one account: `UserBootstrapper` seeds a single user on
first run from `locklane.security.bootstrap-username`/`-password` and every subsequent
login is that one account (`engine/src/main/java/dev/locklane/engine/security/
UserBootstrapper.java`). `ProjectRecord` (`engine/src/main/java/dev/locklane/engine/
persistence/ProjectRecord.java`) carries no owner at all, `workarea_path`
(`engine/src/main/resources/db/migration/V4__create_projects.sql`) is a bare
`workareas/<project-slug>` directory with no per-user segment, and a worktree session's
`owner_username` (`WorktreeSessionRecord`, added by
`V3__AddOwnerUsernameToWorktreeSessions.java`) is set by whichever authenticated request
attaches to it first — "first-attach-claims-it" — rather than derived from anything.
None of this breaks today because there is only ever one account to attach as.

This initiative (#236) adds real multi-user accounts: several people, each with their
own projects, sharing one engine instance. Before any schema or authorization code
changes (#238-#242), the shape of tenancy and authorization has to be a decision this
project can point to, not something that falls out of whichever migration lands first —
`CONSTITUTION.md` §1.3 rules out anything binding that exists only in an issue or PR
thread, and §3's reserved application-surfaces line exists for exactly this moment.

Six things need deciding together, because they interlock: what the isolation boundary
actually is, how the filesystem reflects it, how accounts come to exist at all, what
happens to a user's stuff when the account is removed, how a freshly admin-created
account gets its first real password, and who can see and attach to a running session.

## Decision

1. **`owner_user_id` is the isolation boundary, enforced in the application/query
   layer.** Every project row carries an `owner_user_id`. Every query and every
   authorization check that reads or mutates a project — and everything scoped under a
   project (issues, worktrees, console sessions) — filters or checks against it in
   Java, at the `ProjectController`/`ProjectRepository` layer. The filesystem is never
   consulted to decide who owns what.
2. **Workareas move to `workareas/<user_id>/<project-slug>`, for on-disk organization
   only.** The path gets a per-user segment so a directory listing is legible and two
   users can each have a project of the same slug, but the path is never treated as an
   authorization check — reaching a file under another user's workarea segment,
   however that happened, is still refused by the `owner_user_id` check above, the same
   as it would be for any other path.
3. **The first account ever bootstrapped becomes admin; there is no self-registration,
   ever.** `UserBootstrapper`'s existing "seed one account on first run, no-op once any
   user exists" behavior is kept, with that seeded account marked admin. Every account
   after it is created by an admin, through an admin-only user-management surface —
   never by a public sign-up form, not even a gated one.
4. **Deleting a user cascade-deletes only what that user owned:** their projects, those
   projects' on-disk workarea checkouts, and any worktree/console sessions tied to
   them. Nothing belonging to another user is touched.
5. **An admin-created account must set a new password on first login.** The admin picks
   a throwaway initial password when creating the account; the account cannot do
   anything else until it has replaced that password with one only the account holder
   knows.
6. **Worktree/console session visibility derives from the owning project's owner (or
   admin), replacing first-attach-claims-it.** A session's visible/attachable owner is
   read from its project's `owner_user_id` (admins can see and attach to any session).
   `owner_username`/`owner_user_id` on the session row becomes a denormalized record of
   that derivation, not something an attach call gets to set.

## Rationale

- **Why `owner_user_id` in the app/query layer, not the filesystem (#1):** a home-server
  deployment with several accounts on one filesystem cannot rely on OS-level file
  permissions to separate tenants — the engine process runs as a single OS user and
  reads/writes every project's files itself, so there is no OS boundary to lean on in
  the first place. The only place an authorization decision can actually be enforced is
  where the request is handled. Putting the enforcement there also means it protects
  every access path uniformly (REST endpoints, the WebSocket session endpoint, future
  admin tooling), rather than depending on whichever code path happens to touch disk.
- **Why the workarea path still gets a `<user_id>` segment even though it isn't the
  boundary (#2):** operational legibility — being able to look at `workareas/` on disk
  and see whose is whose, and avoiding a slug collision between two users' projects —
  is worth having even though it carries no security weight. Naming it explicitly as
  "organizational only" up front forecloses the tempting shortcut of later treating path
  containment as an authorization check, which is exactly the mistake #1 rules out.
- **Why first-bootstrapped-is-admin with no self-registration (#3):** `UserBootstrapper`
  already seeds exactly one account with no user interaction, so making that account
  admin costs nothing new operationally — it is the same "there must always be at least
  one account to log in as" guarantee the bootstrapper exists for, just with a role
  attached. Closing off self-registration entirely (not merely "invite-only" self-serve
  signup) matches the deployment model in ADR-002: a home server one person, or a small
  trusted group, runs for themselves — there is no product need for strangers to be able
  to create accounts, and every open self-registration surface is attack surface with no
  offsetting benefit here.
- **Why cascade-delete is scoped to only what the user owned (#4):** a project, its
  on-disk checkout, and its sessions are only ever useful in the context of the account
  that created them — nothing else in the schema references them the way, say, a shared
  team resource would. Leaving them behind after the owning account is gone would strand
  disk space and dangling rows with no owner to attribute them to or clean them up
  later; deleting them together with the account keeps "the user is gone" and "the
  user's stuff is gone" the same event instead of two that can drift apart.
- **Why forced password change on first login (#5):** an admin-created account starts
  from a password the admin chose and therefore knows, which is not meaningfully
  different from having no password at all from the new account holder's point of view.
  Requiring a change before the account can do anything else is the smallest guardrail
  that turns "the admin knows my password" into a fact that stops being true the moment
  the account holder actually uses the account.
- **Why session visibility derives from the project owner instead of first-attach (#6):**
  first-attach-claims-it was never an ownership model — it was a placeholder that only
  worked because every attach came from the same single account. In a multi-user world
  it becomes a real bug: whoever happens to attach to a freshly created session first
  becomes its "owner," which could just as easily be a different account than the one
  that owns the underlying project. Deriving visibility from the project's
  `owner_user_id` instead makes ownership a property of who created the project, decided
  once, consistent with #1's isolation boundary, and never a race between whichever
  request lands first.

## Alternatives considered

- **Filesystem-level isolation** (separate OS users or containers per account,
  filesystem permissions as the actual boundary) — rejected for #1/#2. It would require
  the engine to run project workareas under per-user OS accounts or per-user containers,
  a deployment model change far outside this initiative's scope and in tension with
  ADR-002's single always-on server process; it also does not by itself protect
  anything reached through the API rather than through a shell, which is every real
  access path this app has.
- **Shared/team-owned projects** (a project with multiple owners or a shared workspace,
  rather than exactly one `owner_user_id`) — rejected for #1, for now. Nothing in this
  initiative's goal calls for sharing a project between accounts, and a single required
  owner is the simpler schema; a future ADR can add sharing on top of `owner_user_id`
  (e.g. a join table) without reopening this one, since single ownership is a special
  case of a more general model rather than something a later change would have to undo.
- **Leaving `workareas/<project-slug>` flat and disambiguating by database id alone**
  — rejected for #2. It reads fine in a query but is unusable for a human looking at the
  filesystem directly (backups, manual recovery, debugging a stuck checkout), and
  disambiguating collisions by suffixing an id onto the slug is uglier than a directory
  level that already carries meaning.
- **Invite-only self-service signup** (an admin issues an invite link/token, the
  recipient sets up their own account) — rejected for #3. It is real self-registration
  with an extra gate in front of it, not the absence of self-registration the issue
  asks for, and it adds an email/token-delivery mechanism this app has no other need
  for. Admin-created-account-plus-forced-password-change (#3 + #5) gets the same
  outcome — a real person controls the final password, never the admin — without that
  machinery.
- **Soft-delete / orphan-and-keep users' projects on account deletion** — rejected for
  #4. Keeping a deleted account's projects around ownerless (or reassigned to admin by
  default) creates exactly the kind of stranded, unauthorized-feeling data a cascade
  avoids, and silently reassigning another person's project to admin is a bigger
  surprise than deleting it. A project that matters enough to keep past its owner's
  deletion is a transfer-of-ownership feature, which is not part of this initiative's
  goal and can be its own future ADR if it turns out to be needed.
- **Emailed password reset / "set your own initial password" link** instead of a
  forced change on first login (#5) — rejected because this deployment has no outbound
  email integration (ADR-002's stack has no mail sender), and building one only to
  deliver one email per new account is disproportionate to the problem. A temporary
  admin-chosen password plus a mandatory first-login change needs no new infrastructure
  and closes the same gap.
- **Keep first-attach-claims-it, but scope the claim to the attaching user's own
  projects** — rejected for #6. This still lets whichever request attaches first decide
  ownership rather than the project's actual owner, it just narrows the blast radius of
  the same race; it is a smaller version of the bug, not a fix for it. Deriving
  visibility from the project owner removes the race entirely instead of shrinking it.

## Consequences / revisit triggers

- Every project-scoped read or write in `ProjectController`/`ProjectRepository` (and
  anything reading project-scoped data — issues, worktrees, console sessions) needs an
  `owner_user_id` check added; #238-#242 carry that work, not this ADR.
- `EngineUserDetailsService`/`SecurityConfig` need an admin role/authority and admin-only
  route matchers for the future user-management endpoints; the login flow needs a
  "must change password" state that blocks every other authenticated action until
  cleared, similar in shape to the existing pending-2FA state in
  `TwoFactorAwareLoginSuccessHandler`.
- The `workareas/<project-slug>` → `workareas/<user_id>/<project-slug>` move is a
  breaking on-disk layout change for any existing deployment; the migration that
  implements it needs to move existing checkouts, not just start writing new ones to the
  new path.
- Cascade-deleting a user's workarea checkouts means the delete path does real
  filesystem removal, not just row deletion — it has to handle a live worktree/console
  session on that project the same way project deletion already has to.

Any of these reopens this decision, as a new ADR:

1. **Project sharing becomes a real requirement** — multiple accounts needing genuine
   co-ownership of one project, not just an admin being able to see everything. The
   single-`owner_user_id` model in Decision 1 would need to grow into a join table or
   role-per-project model.
2. **A deployment needs to survive an account being removed while its projects live
   on** — e.g. a team lead's account is deactivated but their projects should transfer
   to someone else rather than disappear. Decision 4's unconditional cascade would need
   a transfer-of-ownership step in front of it.
3. **Outbound email (or another out-of-band delivery channel) is added to the stack**
   for an unrelated reason. Decision 5's forced-first-login-password-change could then
   be replaced or supplemented by an emailed reset link, which was rejected above only
   because no such channel exists yet.
4. **A second maintainer or a genuinely multi-tenant (not just multi-user) deployment
   model is adopted** — e.g. isolating tenants at the OS/container level for reasons
   beyond this app's own authorization checks. The filesystem-isolation alternative
   rejected above becomes worth reconsidering as a defense-in-depth layer on top of,
   not instead of, Decision 1's application-layer enforcement.
