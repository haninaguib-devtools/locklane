# 505 — Fix GitHub project push failing for SSH-authenticated users
Issue: #505

## Asked
Creating a new GitHub-backed project (Add Project dialog → "new GitHub project") fails
to push the initial commit for anyone whose git/GitHub authentication is SSH-based,
because the flow builds an HTTPS remote and pushes with no credentials of its own —
it depends entirely on whatever ambient git credential setup happens to already exist
on the host, which fails outright for an SSH-only user in a non-interactive subprocess.
Separately, when the push fails, the real git error is discarded from the WARN log, so
there's no way to diagnose the failure from the logs alone.

## Done when
- Creating a new GitHub project succeeds for a user whose only configured GitHub
  credential is the per-project `github_token` already stored for that project — the
  push authenticates using that token rather than relying on the host's ambient git
  setup.
- On push failure, the WARN log includes the captured stdout/stderr from the failed
  `git remote add` / `git push` command.
- Existing `ProjectCheckoutService` tests continue to pass, and new tests cover the
  push-failure logging and the token being wired into the push.

## Explicitly not
- No user-facing choice between HTTPS and SSH remotes, and no SSH-key-based push path —
  the existing per-project token over HTTPS is sufficient (per the issue's own
  Non-goals).

## Decisions made along the way
- The token is embedded as HTTPS Basic-auth credentials in the `origin` URL used for
  `git remote add`/`git push` (`https://x-access-token:<token>@...`) rather than an env
  var or credential helper — `x-access-token` is the conventional username GitHub
  accepts alongside a PAT/installation token as the password, and this is the mechanism
  the issue itself suggested; raw `git push` has no built-in use for a bare `GH_TOKEN`
  env var the way the `gh` CLI does (the drive session, 2026-09-01).
- Only the push step (`setUpLocalRepoAndPush`) is wired to the token — `gh repo create`
  is left untouched: the reported failure is specifically the push (the log line shows
  `gh repo create` already succeeding via the host's ambient `gh` auth), and `gh repo
  create`'s own failure path already logs captured stderr today (the drive session,
  2026-09-01).
- The failure log always names `project.gitUrl()` (the plain, credential-free URL),
  never the token-embedded one, even though git itself already redacts embedded
  credentials from its own error text — belt-and-suspenders against ever printing a
  secret to the log (the drive session, 2026-09-01).
- `authenticatedUrl` only rewrites URLs starting with `https://`, leaving anything else
  (there is none in practice today — `createNewProject` always builds an
  `https://github.com/...` URL) unchanged rather than guessing at credential syntax for
  a scheme it doesn't recognize (the drive session, 2026-09-01).
- Adding a `TokenCipher` constructor dependency to `ProjectCheckoutService` forced every
  test file that constructs it directly (`UserCascadeDeleteServiceTest`,
  `AdminUserControllerTest`, `ProjectControllerTest`, beyond `ProjectCheckoutService`'s
  own test) to pass one too — a compiler-forced ripple from the file this task's scope
  names, not a scope expansion (the drive session, 2026-09-01).

## Deviations / notes
- None.
