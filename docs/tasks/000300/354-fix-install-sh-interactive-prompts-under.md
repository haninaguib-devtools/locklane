# 354 — Fix install.sh interactive prompts under curl | bash
Issue: #354

## Asked
`install.sh`'s own usage comment says to run it as `curl -fsSL <url> | bash`. When run
that way, its interactive prompts (port, extra allowed origins, bootstrap
username/password) don't get a chance to read from the terminal: the `read` builtin and
the shell's own script parser share the same stdin stream, so a `read` ends up consuming
the *next line of the script's own source* as if it were the typed answer. That skips
the `origins="http://localhost:$port"` assignment a few lines later, and the script then
dies with `origins: unbound variable` under `set -u`. Fix is to make every interactive
`read` in the prompts section read explicitly from `/dev/tty` instead of stdin, so
prompting works the same whether the script is piped from curl or run from a local copy.

## Done when
- Running `curl -fsSL <url-to-install.sh> | bash` prompts correctly, in order, for
  port, extra allowed origins, bootstrap username, and password/confirm, and completes
  without an "unbound variable" error or any other crash.
- Running `bash install.sh` from a local copy (non-piped) still prompts and completes
  correctly, unchanged from today.
- The written `application-locklane.properties` contains
  `locklane.security.allowed-origins` correctly reflecting `http://localhost:<port>`
  plus any extra origins entered.

## Explicitly not
Adding a non-interactive/flag-driven mode for CI or unattended installs — this task
only makes the existing prompts work correctly under `curl | bash`.

## Decisions made along the way
- none

## Deviations / notes
- none
