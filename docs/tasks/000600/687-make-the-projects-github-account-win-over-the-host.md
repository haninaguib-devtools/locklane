# 687 — Make the project's GitHub account win over the host's git credential helper
Issue: #687

## Asked
On a Mac, every git command the engine runs for a project authenticates as whatever
github.com identity the laptop's keychain holds, not as the GitHub account chosen for
the project. Apple's Command Line Tools ship a system gitconfig with
`credential.helper = osxkeychain`, which git consults before Locklane's own inline
credential helper (`-c credential.helper=<script>`), because helpers are queried in
config order and git only resets the list on an *empty* `credential.helper` entry.
Linux hosts have no system helper, which is why the bug never showed there. The fix:
precede the inline helper with an empty `credential.helper` reset entry everywhere the
engine installs one — the per-command `-c` arguments, the console session's
`GIT_CONFIG_COUNT`/`GIT_CONFIG_KEY_n`/`GIT_CONFIG_VALUE_n` environment, and the
repo-local config written after clone — so the project's chosen-account token always
wins over any host-configured helper.

## Done when
- `GitCredential.forRemote(<https url>, <token>).command(...)` yields
  `git -c credential.helper= -c credential.helper=<HELPER_SCRIPT> ...` (empty reset
  entry immediately before the inline helper), and `sessionEnvironment()` carries the
  same two entries in order (`GIT_CONFIG_COUNT=2`, key 0 empty, key 1 the helper
  script). `GitCredentialTest` asserts both. `GitCredential.NONE` is unchanged.
- `ProjectCheckoutService.configureCredentialHelper` writes the same two-entry list
  after a clone, idempotently (`git config --get-all credential.helper` shows an empty
  line then the helper script; re-running leaves exactly those two entries, no
  duplicates). `ProjectCheckoutServiceTest` asserts this.
- A real-`git`-against-a-throwaway-bare-repo test in
  `engine/src/test/java/dev/locklane/engine/persistence/` proves a decoy credential
  helper (configured in a throwaway `HOME`'s global gitconfig) is never consulted, or
  its answer discarded, in favour of `x-access-token`/`GH_TOKEN`. Never touches the
  developer's own `~/.gitconfig`.
- The macOS end-to-end symptom (osxkeychain answering for a different account) is
  closed — human-verified only, named here as the one manual check.
- `./mvnw -B test` passes.

## Explicitly not
- No change to how tokens are obtained, stored, renewed, or scoped; SSH remotes are
  untouched.
- No change for a project with no chosen GitHub account — it keeps plain git and
  ambient host credentials.
- Not touching the installer or the launchd/systemd registration, nor any host git
  configuration.
- The `Unable to access jarfile` launchd-relaunch log lines and the
  `IllegalStateException: TEXT_PARTIAL_WRITING` from `GET
  /api/projects/{id}/issues/tree` seen in the same laptop log are separate defects,
  out of scope — each gets its own issue when the human asks for one.

## Decisions made along the way
- none

## Deviations / notes
- The issue's Scope line names `CHANGELOG.md`, but that file is generated entirely
  from squash-commit subjects at release-cut time (`scripts/generate-release-notes.sh`,
  `docs/architecture/releasing.md` § Release notes) — every prior entry in its own
  history is a `Cut release …` commit, never a task PR. Left untouched here; the
  release that ships this fix generates its own entry from this PR's title.
