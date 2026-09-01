# 531 — Fail project bootstrap early when the GitHub token lacks the workflow scope
Issue: #531

## Asked
Creating a new project with the "bootstrap with t-workflow" checkbox ticked fails at
the first push when the engine falls back to the host's `gh` login for credentials and
that login was granted without the `workflow` scope. The bootstrapped checkout carries
`.github/workflows/ci.yml`, and GitHub refuses any push that creates or updates a
workflow file from an OAuth token lacking that scope. Today the project is simply
marked failed and the only clue is a raw `git push` rejection in the engine log:

```
! [remote rejected] main -> main (refusing to allow an OAuth App to create or update workflow `.github/workflows/ci.yml` without `workflow` scope)
```

The engine should check the token's scopes before it builds and pushes the checkout,
fail early with a message that names the missing `workflow` scope and the exact fix
(`gh auth refresh -h github.com -s workflow`), and the README should state which scopes
the host's `gh` login needs so an operator can grant them up front. This is the same
fallback path #513 introduced (`resolveGithubToken` in `ProjectCheckoutService`,
ambient `gh auth token`); #525 fixed the checkout layout, which is what first exposed
the workflow file to the push.

## Done when
- With a stub `gh` whose reported scopes lack `workflow`,
  `createNewProject(…, bootstrapTWorkflow = true, …)` marks the project failed without
  running the installer or attempting `git push`, and logs a single WARN naming the
  project, the missing `workflow` scope, and the `gh auth refresh -h github.com -s
  workflow` command. Covered by a test in `ProjectCheckoutServiceTest`.
- With a stub `gh` whose reported scopes include `workflow`, the bootstrap path proceeds
  exactly as today (existing tests unchanged and green).
- The plain (non-bootstrap) creation path is not gated on `workflow`: a token with only
  `repo` still creates and pushes a plain project. Covered by a test.
- A stored per-project token (#81) is checked the same way when the bootstrap flag is
  set, so the early failure does not depend on which credential source won.
- `README.md` states, next to the install instructions, that the host's `gh` login needs
  the `repo` and `workflow` scopes, with the `gh auth refresh` command for adding
  `workflow` to an existing login: `grep -n "workflow" README.md` shows the line.
- `./mvnw -B test` passes.

## Explicitly not
- Surfacing the failure reason in the project list UI (today the project only shows as
  failed; the reason lives in the engine log). Not part of this fix.
- Requesting or adding the `workflow` scope on the operator's behalf. The engine reports
  what is missing; the operator grants it.
- Changing how t-workflow's installer lays out or names the CI workflow file.

## Decisions made along the way
- The scope gate runs in `createRepoAndPush` *before* `gh repo create`, not only before
  the installer: a token that could never complete the bootstrap push then leaves no
  empty repository behind on GitHub. The issue's "before it builds and pushes" is a
  subset of this (agent, from the plan on the issue, 2026-09-01).
- The token is resolved for the check exactly as `resolveGithubToken` resolves it for
  the push — stored per-project token first, then the ambient `gh auth token` — so the
  early failure never depends on which credential source wins. The WARN says which
  source lacked the scope; for a stored token it adds that storing a token with the
  scope is the alternative to refreshing the host login (agent, 2026-09-01).
- The "which scopes does this token carry" lookup is an injected
  `Function<String, Optional<Set<String>>>` on a fourth test-only constructor, in the
  same pattern as #513's ambient-token supplier and #525's install command; the
  `@Autowired` constructor is unchanged. Production runs `gh api -i user` with
  `GH_TOKEN=<token>` (the way `CliGhClient` already pins gh to a token) and parses the
  `X-OAuth-Scopes` response header into whole comma-separated tokens (agent, 2026-09-01).
- The gate fails **open** whenever scopes are unknowable: `gh api` exiting non-zero, or
  no / blank scope header (fine-grained PATs and GitHub App tokens carry no classic
  scopes). Only a positively reported list lacking `workflow` refuses; a missing token is
  left to the existing "No GitHub credentials available" path. Logged at INFO when
  skipped this way (agent, from the plan's risk list, 2026-09-01).
- The end-to-end tests drive `createNewProject(…, true, …)` with a same-thread executor
  and stubs only — and guard against a regressed gate reaching the real `gh repo create`
  by naming an org that cannot exist, stubbing the installer to leave a marker file, and
  asserting exactly one WARN: a leak would add a second WARN (404, or gh missing on CI)
  and fail the test rather than create anything (agent, 2026-09-01).

## Deviations / notes
- The plan's targeted check `./mvnw -B test -pl engine -Dtest=ProjectCheckoutServiceTest`
  does not resolve on its own — the engine module depends on the `client` artifact, so
  `-am` is required: `./mvnw -B test -pl engine -am -Dtest=ProjectCheckoutServiceTest
  -Dsurefire.failIfNoSpecifiedTests=false`. Same check, corrected invocation.
- The branch was created from `origin/main` (5d14994): the primary checkout's local
  `main` was 22 commits behind-only and is checked out in another worktree, so it was
  left alone rather than fast-forwarded from here. Diffs for the checks were taken
  against `origin/main...HEAD` for the same reason.
- Dead end: the first version of the plain-path test asserted the pushed branch was
  `main`; the plain `git init` path takes the host's default branch name, so the test
  now reads the branch the service recorded.
- Pre-existing, out of scope, proposed as issues rather than touched: (1) `retry()`
  re-runs `clone`, not the create-and-push flow, so a project that fails at this gate is
  re-created rather than retried; (2) `gh repo create` always uses gh's active login
  while the push (and now the gate) may use a stored per-project token — #532's area.
