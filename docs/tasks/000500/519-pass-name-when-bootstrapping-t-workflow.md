# 519 — Pass --name when bootstrapping t-workflow for new projects
Issue: #519

## Asked
Creating a new project with t-workflow bootstrapping enabled fails immediately:
`ProjectCheckoutService` runs the t-workflow installer via `curl -fsSL <url> | bash`
with no `--name` flag and no TTY attached. The installer's only interactive prompt
(asking for the project name) needs a terminal it doesn't have, so it dies right away
with `installer: no terminal available for prompts. Pass --name (see --help).` The
project's name is already known at the call site (`project.name()`), so it just needs
to be passed through to the installer.

## Done when
- `ProjectCheckoutService`'s t-workflow install invocation passes `--name <project
  name>` to the installer, via `bash -s -- --name '<name>'` — flags placed after a
  plain `| bash` are swallowed by bash itself rather than forwarded to the script.
- The project name is safely quoted/escaped where it's interpolated into the shell
  command (or the command is built as an argv array instead of a shell string), so a
  project name containing shell metacharacters can't inject into the invocation.
- Creating a new project with t-workflow bootstrapping enabled succeeds without the
  "no terminal available for prompts" error.

## Explicitly not
Whether the host/container running the engine has a git identity configured
(`user.name`/`user.email` or `GIT_AUTHOR_*`/`GIT_COMMITTER_*`) for the installer's
later commit step — separate, unconfirmed question, out of scope here.

## Decisions made along the way
- Passed the install URL and project name as separate `bash -c` positional
  arguments (`$1`/`$2`) rather than string-interpolating them into the script text,
  so quoting can't be gotten wrong and a project name with shell metacharacters
  can't inject into the invocation (haninaguib, 2026-09-01).

## Deviations / notes
- none
