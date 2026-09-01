# 513 — Fix push failure on newly created projects (no GitHub credentials yet)
Issue: #513

## Asked
When someone creates a new project, Locklane creates the GitHub repo successfully but
then fails to push the initial code to it, surfacing an opaque error like `fatal: could
not read Username for 'https://github.com': Device not configured`. `gh repo create`
succeeds because it rides on whatever identity `gh` is already logged in as on the host,
but the subsequent `git push` only authenticates when a per-project GitHub token is
already stored — and nothing in the create-project flow populates that token up front,
so a freshly created project has no credentials for its own first push and git's
interactive credential prompt fails against a subprocess with no attached TTY.

## Done when
- Creating a new project succeeds through both repo creation and the initial push,
  without the user having set a per-project GitHub token beforehand.
- If no usable credentials can be found at all, the failure is a clear, actionable error
  rather than a raw git "Device not configured" message.
- Existing behavior for projects that do have a per-project token stored is unaffected.

## Explicitly not
- No change to `createProject`'s (existing-repo import) clone path — it never builds an
  authenticated push URL and is outside this issue's reported failure.

## Decisions made along the way
- The push now falls back to `gh auth token` — the same identity `gh repo create` just
  used to create the repository — whenever no per-project token is stored yet, rather
  than requiring the user to set one before their first push ever succeeds (the drive
  session, 2026-09-01).
- When neither a stored token nor `gh auth token` yields anything, the push bails before
  even running `git remote add`/`git push`, logging a clear "No GitHub credentials
  available" WARN and marking the project failed — never letting git reach its own
  opaque interactive-prompt failure (the drive session, 2026-09-01).
- The ambient-token lookup is injected via a package-private `Supplier<Optional<String>>`
  constructor parameter (mirroring `IssueWorktreeService`'s existing dual-constructor,
  `@Autowired`-on-primary pattern) rather than calling `gh auth token` unconditionally
  from a static method, so `ProjectCheckoutServiceTest`'s existing contract — exercising
  `setUpLocalRepoAndPush` without ever invoking the real `gh` CLI — still holds for every
  test that doesn't specifically exercise the new fallback (the drive session,
  2026-09-01).
- `resolveGithubToken` still checks the stored per-project token first; the ambient
  lookup only runs when that's empty, so existing per-project-token behavior (#505) is
  unaffected and never shells out to `gh` in that case.

## Deviations / notes
- None.
