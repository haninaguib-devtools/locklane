# 384 — Stop storing the bootstrap password in application-locklane.properties
Issue: #384

## Asked
`install.sh` prompts for a bootstrap login password and writes it in plaintext into
`~/.locklane/application-locklane.properties` as `locklane.security.bootstrap-password`,
where it stays for the life of the install. The engine reads it exactly once:
`UserBootstrapper` returns immediately if any user already exists, so the first startup
bcrypt-hashes the value into the database and every startup after that reads it and
throws it away. The file is mode 600, so it is not world-readable, but a credential with
a one-run lifetime is being kept for ever — it survives into backups, into copies of the
config directory, into screen shares, and into any support request where someone pastes
their configuration. Seed the admin account during installation instead, and leave
nothing on disk but `server.port` and `locklane.security.allowed-origins`.

## Done when
- After a fresh `install.sh` run,
  `grep -c bootstrap-password ~/.locklane/application-locklane.properties` returns 0, and
  the file contains only `server.port` and `locklane.security.allowed-origins`.
- The prompted username and password still log in: the admin account is already in the
  database the first time the server is started normally, so the seeding run and the
  ordinary run must use the same data directory — not a second, empty database.
- The password never reaches a process argument list — it is passed through the
  environment or stdin, never as a command-line argument, so it is not visible in `ps`
  output while the install runs.
- `update.sh`, run on an install whose properties file still carries a
  `bootstrap-password=` line from an earlier install, removes that line and leaves every
  other line byte-for-byte unchanged. The account already exists on such an install, so
  the value is dead.
- `./mvnw -B test` and `./.t-workflow/scripts/consistency-check.sh` pass.
- Human judgment: a real `install.sh` run against a clean `~/.locklane` ends with a
  working login and no password anywhere on disk.

## Explicitly not
- No change to how passwords are hashed or how login works.
- No password rotation, reset, or change-password flow.
- No multi-user account management.
- No change to which port or origins the installer prompts for.

## Decisions made along the way
- The engine gained a seed-only mode (Claude, 2026-08-30). The issue's Scope allows
  touching `UserBootstrapper.java` and `application.yml` "only if passing the password
  through Spring Boot's existing environment binding turns out to be insufficient", and
  it is: the environment gets the value in, but `java -jar locklane.jar` then starts the
  web server and never returns, so the installer has no run that seeds and stops.
  Spring Boot's own `--spring.main.web-application-type=none` is not a way out —
  `SecurityConfig` builds a `SecurityFilterChain` from `HttpSecurity`, which only exists
  in a servlet context, so the context would fail to start. Hence
  `locklane.security.seed-only`: a normal full start that shuts itself down as soon as
  the account is in the database.
- The seeding run is invoked with `--server.port=0` (Claude, 2026-08-30) so it cannot
  collide with an instance already listening on the configured port. A command-line
  argument also beats an inherited `SERVER_PORT` environment variable, which a terminal
  launched from a running locklane really does carry.
- A seeding run that finds an account already present exits `3`
  (`UserBootstrapper.EXIT_ALREADY_SEEDED`) rather than `0` (Claude, 2026-08-30).
  Without it, re-running `install.sh` to reset a forgotten password would print "the
  account is ready" and the person would then fail to log in. `install.sh` now says
  plainly that what they typed was not applied.
- `seed-only` is bound with an inline `:false` default (Claude, 2026-08-30):
  `engine/src/test/resources/application.yml` replaces the main one wholesale rather
  than merging with it, so a mandatory placeholder would have had to be repeated there.

## Deviations / notes
- `install.sh` now requires `java` at install time, not only at run time, and checks for
  it alongside the existing `gh` checks — the seeding run is the jar itself.
- The seeding run's stdin is `/dev/null`. Under `curl | bash` the script's own source is
  on stdin, and a child that read from it would eat the rest of the script — the same
  trap #354 fixed for the prompts.
- `update.sh` removes only the `bootstrap-password` line. A legacy properties file also
  carries `bootstrap-username`, which is not a secret, and the done-when requires every
  other line to survive byte-for-byte, so that line is deliberately left in place. The
  "port and origins only" end state therefore describes a fresh install; an upgraded one
  keeps its (harmless) username line.
- Verified by hand, not by an automated test — there is no shell-test harness in this
  repo and the seed-only path ends in `System.exit`, which a `@SpringBootTest` cannot
  exercise. What was run: `install.sh` end-to-end against stubbed `gh`/`java` into an
  isolated `user.home`, twice (fresh, then already-seeded); a normal server start
  against the same data directory, logging in over `/api/auth/login` with the seeded
  credentials; `/proc/<pid>/cmdline` of the live seeding run checked for the password;
  `update.sh` against a legacy properties file, compared byte-for-byte.
