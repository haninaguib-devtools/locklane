Read `.t-workflow/AGENTS.md` first — the delivery workflow for this repository.

## Project notes

*(this repository's own session-start instructions)*

## Constraints

1. Spring Boot engine + Angular PWA client + SQLite for durable non-binding state, with
   one persistent PTY (pseudo-terminal) session per git worktree, reattachable from any
   browser, from anywhere — never a job queue (ADR-100).
2. An agent-created, per-issue worktree is removed automatically once its issue is
   confirmed closed, its git status is clean, and no agent session is attached — a
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
4. A project-agent worktree (no issue of its own) is removed on tab close, and by
   the same periodic sweep as a backstop, once its session has ended and its git
   status is clean, and either its HEAD is detached and an ancestor of the project's
   default branch on origin, or a branch is checked out whose work has already landed
   there — even under a rewritten SHA, e.g. via squash-merge — while a checked-out
   branch that still carries real, un-landed work is left alone unconditionally, the
   same as before — a second, distinct guarded exception to ADR-005, alongside point
   2's rather than folded into it (ADR-104, amended by ADR-107 and ADR-108).
5. A project is visible and operable only to the account that owns it, and a
   worktree/agent session only to the owner of its project — no role, administrator
   included, is exempt; administrators manage accounts and nothing more (ADR-105,
   superseding the administrator exemption in ADR-101 Decisions 1 and 6).
6. On macOS the server is a launchd agent in the `user/<uid>` domain, its plist
   declaring `LimitLoadToSessionType` = `Background` so it loads without a graphical
   login (ADR-111, superseding ADR-110 Decision 1); a refused `launchctl bootstrap` leaves the
   plist in place; and any change to the launchd registration ships only after
   `.github/workflows/mac-lifecycle.yml` has passed against a real `launchctl`, never
   on the stub harness alone (ADR-110 Decisions 2 and 3).
7. Wherever a person reads the product, a tab running an AI coding agent is an
   *agent*, a plain shell is a *shell*, a resumable past agent conversation is a
   *conversation*, *session* is the engine's own word for any persistent PTY and is
   never shown to a user, and *agent CLI* names the program an agent runs where a
   sentence must tell it from a running agent. On-the-wire and persisted names —
   session id shapes, database schema, REST paths, WebSocket event names, route
   paths, localStorage keys — are compatibility surfaces the vocabulary never forces
   to change, and pre-existing ADRs, CHANGELOG entries and task records keep their
   wording (ADR-112).


## Skills of this repository

| Skill | Stage |
|---|---|
| `/l-release` | Cut a release with one command — gates on the version (`scripts/release.sh gate`), opens one task carrying the version's `CHANGELOG.md` section and the `<revision>` bump to the next snapshot, drives it to a single merge-and-dispatch confirmation, then dispatches and verifies the release (`scripts/release.sh dispatch`). One human stop (ADR-109, superseding ADR-106 D1/D2). |
