# 532 — Let both add-project forms choose which gh account a project acts as
Issue: #532

## Asked
An operator who uses several GitHub accounts on one machine (a personal and a work
login, say) has no way to say which of them a project belongs to. The add-project
popup's "create project" form only asks for an org, a name, and the t-workflow
checkbox; the engine then runs `gh repo create` as whichever `gh` account happens to be
active on the host and pushes the first commit with that same account's token from
`gh auth token` (#513). The "existing repository" form has the mirror problem: an
operator routes each identity through an SSH host alias in `~/.ssh/config`, so
importing `git@thyme.github.com:hani-thyme/ideation_1.git` clones fine with the work
key, but the engine's issue and PR fetches run `gh` inside that checkout as the host's
active account, which cannot see the repository. Verified on gh 2.98.0: in that
checkout `gh issue list` as the active login fails with `Could not resolve to a
Repository with the name 'hani-thyme/ideation_1'`, while the same command with
`GH_TOKEN` set to the work account's token succeeds — `gh` maps the SSH alias back to
`github.com` on its own, so only the identity is wrong. The SSH alias never applies to
the create path at all, because that path is entirely HTTPS-plus-token.

Add a "GitHub account" picker to both forms, listing the accounts `gh` is logged into
on the engine host and defaulting to the active one. On create, the engine creates the
repository and pushes under the chosen account's token, obtained with
`gh auth token --user <login>` and passed as `GH_TOKEN` so the host's active account is
never switched. On both create and import, the chosen account's token is stored as the
project's own encrypted token (#81) at creation, so the project's issue and PR listing
act as that identity from the first fetch. The import's `git clone` itself is unchanged
and keeps using whatever the URL implies (SSH alias and key included).

## Done when
- A new authenticated engine endpoint (account-scoped like `/api/agents/installed`, not
  project-scoped) returns the list of `gh` logins on the host with which one is active,
  parsed from `gh auth status --json hosts`. An empty list, or `gh` not installed,
  returns an empty list rather than an error. Covered by an engine test with a stubbed
  `gh` output.
- Both the create form and the existing-repository form show a "GitHub account" select
  populated from that endpoint, preselecting the active login. With exactly one login
  the select still shows it. With zero logins each form shows a hint that the host
  needs `gh auth login`; the create button is disabled, the import button stays enabled
  (an import without a token behaves exactly as today). Covered by a component spec.
- `POST /api/projects/new` and `POST /api/projects` accept an optional `githubLogin`.
  When set, the engine obtains `gh auth token --user <login>` and stores it encrypted
  as the project's token before any clone or push; on the create path `gh repo create`
  and the first push additionally run with `GH_TOKEN` set to that token. When absent,
  behaviour is exactly today's. Covered by `ProjectCheckoutServiceTest` with a stubbed
  `gh`.
- After importing an existing repository with `githubLogin` set, the project's issue
  and PR fetch runs with that stored token (the existing #81 path), so a repository
  visible only to that account lists correctly. Covered by a test asserting the token
  is stored on import; the fetch path itself is already tested.
- A `githubLogin` naming a login `gh` does not know marks the project failed with a
  WARN naming the login; no repository is created on GitHub and no clone is attempted.
  Covered by a test.
- `./mvnw -B test` passes and the client spec suite passes.

## Explicitly not
- Logging into additional `gh` accounts from the UI. The picker only lists what
  `gh auth login` already set up on the host.
- Changing how the import clones: the URL, SSH alias, and key selection stay the
  operator's `~/.ssh/config` business.
- Checking the chosen account's token scopes. That is #531's concern and composes with
  this once both land.

## Decisions made along the way
- The account listing is `GET /api/github/accounts` → `{"accounts":[{"login","active"}]}`,
  served by a new `GhAccountsController`/`GhAccountsService` pair under
  `engine/.../github/`, and lists only the `github.com` host's accounts from
  `gh auth status --json hosts` — the create path hard-wires `https://github.com/` and
  `gh auth token --user` defaults to that host (agent, per the plan, 2026-09-01).
- `gh auth status --json hosts` is run per request, not cached at boot the way the
  installed-agents list is: an operator can `gh auth login` a second account while the
  engine runs, and the picker should show it on the next open. Verified on gh 2.98.0
  that with `--json` the command exits 0 even with no accounts (printing
  `{"hosts":{}}`), so a missing `gh` binary is the only failure the service has to
  swallow — it yields an empty list (agent, 2026-09-01).
- `SecurityConfig` gains exactly one matcher, `/api/github/**` → authenticated, plus
  its javadoc mention. The chain ends in `anyRequest().permitAll()`, so without the
  line the host's list of logins would be public. Named in the plan as an addition
  beyond the issue's Scope line; `SecurityConfig` is a reserved future protected
  surface (CONSTITUTION §4.3), not yet enforced by `protected-paths.sh` (agent,
  2026-09-01).
- Per-login token resolution happens at the start of the async job, before anything
  else: resolve `gh auth token --user <login>`, store it encrypted on the row, then
  proceed. A failed lookup marks the project FAILED with a WARN naming the login (and
  `gh`'s stderr, which carries no token) before any `gh repo create`, clone, or workarea
  directory exists, so `retry` behaves as today and there is nothing to clean up
  (agent, per the plan, 2026-09-01).
- Test seam: a further package-private constructor substitutes the `gh` executable
  (default `"gh"`), mirroring the `installCommand` seam from #525; tests point it at a
  stub script that dispatches on its arguments (`auth token --user <login>` prints a
  token or exits 1 with gh's real wording; `repo create` records `$GH_TOKEN` and its
  arguments to a file). The older `Supplier<Optional<String>>` seam for the ambient
  `gh auth token` (#513) is kept as-is so its existing tests stay untouched (agent,
  2026-09-01).
- `GH_TOKEN` is only ever put into `ProcessBuilder.environment()`, never into a command
  array or a log line (agent, per the plan, 2026-09-01).
- The client omits `githubLogin` from the request body entirely when no account is
  chosen, so the existing service/component specs' exact-body assertions stay true and
  the engine sees `null` rather than `""` (agent, 2026-09-01).
- The "zero accounts disables create" rule lives in the template's `[disabled]`
  binding only, as the issue words it; `submitCreate()` carries no matching code guard,
  so the pre-existing create-mode specs (which call `submit()` without rendering) stay
  untouched. The create button is also held disabled until the accounts request has
  answered, so it is never briefly enabled with no account behind it (agent,
  2026-09-01).
- `createRepoAndPush` became package-private so its test can drive it against a
  throwaway local bare repo standing in for the just-created GitHub remote; a
  pre-receive hook in that bare repo captures the pushing process's `GH_TOKEN`, which
  is how "the first push runs with `GH_TOKEN`" is asserted without a network (agent,
  2026-09-01).

## Deviations / notes
- `client/src/app/app.component.spec.ts` is touched only to flush the new
  `/api/github/accounts` request in the three specs that mount the real popup; its
  `afterEach` verifies no outstanding requests. Named in the plan's Allowed paths.
- Scope overlap with #531, which merged to `main` (PR #534) while this task was in
  flight: both edit `ProjectCheckoutService.java` and its test. Rebased onto it; the
  human directed the agent to resolve the seven conflict hunks (2026-09-01). Both
  test-only seams now ride one nine-argument constructor (#531's `tokenScopes`, then
  this task's `ghExecutable`), and in `createRepoAndPush` the chosen account's token
  is resolved and stored *before* #531's workflow-scope gate runs, so that gate judges
  exactly the token the bootstrap push will use — covered by a new test
  (`createRepoAndPushRunsTheWorkflowScopeGateOnTheChosenAccountsToken`).
