# 572 — Give git inside a project console session the project's GitHub token
Issue: #572 · Split from: #569

## Asked
A Claude session the engine starts for a project already acts as the project's GitHub
account for `gh`, because the engine puts the stored token into the session as
`GH_TOKEN`. Plain `git` in the same session does not read that variable, so `git
fetch`/`git push` only worked when the host had its own SSH key or credential helper —
and with gh's helper, as gh's *active* account, not the project's. The session
environment should give git the same token through a mechanism git honours, so `git`
and `gh` both act as the project's account with no host-level setup. HTTPS remotes
only; SSH remotes and projects with no stored token are unchanged.

## Done when
- In a project console session for an HTTPS-remote project with a stored token, on a
  host with no credential helper and no SSH key, `git fetch origin` and `git push`
  succeed and `gh api user -q .login` prints the project's account. Verified by hand.
- The credential reaches git via `GIT_ASKPASS` or `GIT_CONFIG_COUNT`/`KEY_n`/`VALUE_n`
  inline `credential.helper` reading the token from the environment — never written
  into `.git/config`, the remote URL, or the command line, never printed at startup.
- The helper #569 introduced is reused: `grep -rn 'GIT_ASKPASS\|credential.helper'
  engine/src/main` shows a single definition.
- An SSH-remote project, or one with no stored token, gets no git credential variables
  (unit test on the environment map).
- `./mvnw -B test` passes.

## Explicitly not
- No change to how the token is obtained or stored.
- No change to the SSH path.
- No change to `gh` behaviour in sessions.

## Decisions made along the way
- `GIT_CONFIG_COUNT`/`GIT_CONFIG_KEY_0`/`GIT_CONFIG_VALUE_0` over `GIT_ASKPASS`
  (agent, 2026-09-02): it installs the exact same helper script #569/#551 already use,
  so there is one definition (`GitCredential.HELPER_SCRIPT`, keyed by the new
  `GitCredential.HELPER_KEY`) and no extra script file on disk.
- The variables are produced by a new `GitCredential#sessionEnvironment()` and merged
  into `ProjectConsoleService#environmentFor`, which keeps emitting `GH_TOKEN` alone
  for an SSH remote (gh still needs it there).

## Deviations / notes
- none
