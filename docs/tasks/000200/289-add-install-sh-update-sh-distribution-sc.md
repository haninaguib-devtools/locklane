# 289 — Add install.sh/update.sh distribution scripts
Issue: #289

## Asked
Give a new locklane user a single command — copied from the locklane website — that
leaves them with a working local install: it downloads the current engine jar, asks
which port to run on, which extra origins (if any) should be allowed to connect, and a
login username/password, then writes that into a config file under `~/.locklane`. A
second script lets them pull a newer jar later without re-entering any of that.

## Done when
- Running `install.sh` on a machine with nothing under `~/.locklane` leaves exactly
  `locklane.jar`, `update.sh`, and `application-locklane.properties` (mode 600) there,
  with the prompted-for port/origins/credentials written into that properties file.
- Starting the jar with `cd ~/.locklane && java -jar locklane.jar` binds the prompted
  port, accepts the prompted bootstrap login, and only allows the prompted origins
  (plus localhost) — verified by connecting to `/ws/sessions/...` from an allowed and a
  disallowed origin.
- Running `update.sh` replaces `locklane.jar` with the current `latest` release build
  and leaves `application-locklane.properties` byte-for-byte unchanged.
- `./mvnw -B test` and `./scripts/consistency-check.sh` still pass with
  `spring.profiles.active: locklane` added to `application.yml`.

## Explicitly not
- No process supervision: no systemd/launchd unit, no restart-on-crash, no
  run-on-boot. The user (or a future task) launches the jar themselves.
- No hosting of `install.sh` on the locklane marketing website — this task only needs
  the script to exist and work when fetched by URL.
- No switch to versioned (`v*`) release channels — revisit once the project cuts its
  first real version.

## Decisions made along the way
- Both scripts require the `gh` CLI, authenticated, on the host (hani, 2026-08-28) —
  the repo is private, so a plain `curl` can't reach either the release asset or a raw
  file without a token, and the engine itself already assumes `gh` is present and
  authenticated for its own release-check feature (#287,
  `dev.locklane.engine.github.CliReleaseClient`). Reusing that same assumption avoids
  reimplementing GitHub auth in shell.
- `install.sh` fetches `update.sh`'s contents fresh from the repo's default branch
  (`gh api … repos/<repo>/contents/update.sh`, raw Accept header) rather than embedding
  a copy of it inline — one source file, no drift between what's embedded and what's
  checked in.
- The rolling build lives at release tag `latest` (a pre-release, per
  `.github/workflows/release.yml`) — `gh release download latest …` names that tag
  explicitly, since the argument-less "download the latest release" resolution
  excludes pre-releases and would find nothing.
- `allowed-origins` is written as `http://localhost:<prompted port>[,<extra origins>]`
  — the issue's "always keeping localhost allowed" is read as the origin matching the
  port the user just chose to run on, not the shipped defaults' fixed `:4200`/`:8080`.

## Deviations / notes
- none
