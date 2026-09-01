# 491 — Support creating a new GitHub project from the Add Project dialog
Issue: #491

## Asked
Today the Add Project dialog only lets someone attach Locklane to a GitHub repository
that already exists, by pasting its URL. Add a second path in the same dialog: let the
user type a GitHub org and a project name, optionally check a box to bootstrap the new
project with t-workflow, and have Locklane create the repository on GitHub, set up a
local checkout, and register it exactly like an imported project — so someone can start
a brand-new project without leaving Locklane or using a terminal.

## Done when
- The Add Project dialog offers a choice between "Import existing" (unchanged) and
  "Create new", the latter collecting a GitHub org, a project name, and a "bootstrap
  with t-workflow" checkbox.
- Submitting "Create new": creates the GitHub repo via `gh` (private by default),
  creates the workarea directory (`workareas/<ownerUserId>/<slug>`), bootstraps with
  t-workflow's installer or a plain `git init` + minimal `README.md` depending on the
  checkbox, then pushes to `origin`.
- The new project reaches the same `READY` state (with `defaultBranch` populated) as a
  freshly imported project, reusing the existing async, status-tracked create/clone
  flow (`ProjectCheckoutService`, `CLONING`/`READY`/`FAILED`).
- Any failure surfaces to the user as an error and is logged with full detail
  server-side; the project record moves to `FAILED` the same way a failed import does.
- `./mvnw -B test` passes, with automated coverage where the existing test setup
  supports it; a manual check stands in where a subprocess call can't reasonably be
  tested in-process.

## Explicitly not
- No per-project or user-level GitHub credential UI — the server's existing `gh`
  authentication is assumed to already have permission to create repositories in the
  requested org.
- No repository-visibility control in the dialog — created repositories default to
  private.
- No support for a project name that differs from the GitHub repository name.
- No support for importing an existing local, remote-less checkout.

## Decisions made along the way
- `POST /api/projects/new` is a new, separate endpoint from the existing
  `POST /api/projects` (import) rather than an overloaded request shape, mirroring the
  UI's own explicit two-mode split (Hani, 2026-08-31).
- `gh repo create <org>/<name> --private` runs without `--source .`/`--push`: the local
  checkout is built and pushed as its own separate step (matching the issue's stated
  step order), so a failure partway through (repo created, local push failed) still
  leaves a diagnosable `FAILED` project rather than a half-finished `gh` invocation
  (Hani, 2026-08-31).
- The non-bootstrap path sets a local (not global) `git config user.email`/`user.name`
  before committing the minimal README — no code in this repo previously ran `git
  commit` server-side, and nothing guarantees the host's git has a global identity
  configured (confirmed: only test helpers set `user.email`/`user.name`, and only
  locally per throwaway repo) (Hani, 2026-08-31).
- `ProjectCheckoutService.setUpLocalRepoAndPush` (everything after the GitHub repo
  itself exists) is package-private specifically so a test can exercise the whole local
  init/bootstrap/push sequence against a throwaway local bare repo standing in for the
  just-created GitHub remote, without ever invoking `gh` — `gh repo create` itself has
  no automated coverage; verified manually instead (Hani, 2026-08-31).

## Deviations / notes
- none
