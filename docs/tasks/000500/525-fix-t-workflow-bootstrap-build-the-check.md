# 525 — Fix t-workflow bootstrap: build the checkout at the workarea root, not a subdirectory
Issue: #525

## Asked
Creating a new project with the "bootstrap with t-workflow" option always fails. The
engine runs the t-workflow installer with the project's workarea directory as the
working directory and passes `--name <name>`, but the installer's contract is to create
the project at `<cwd>/<name>` — a subdirectory — never at the working directory itself.
The install exits 0, the repository lands at `<workarea>/<name>/`, and the engine then
runs `git branch --show-current` at the workarea root, finds no repository, logs
`Could not determine the default branch for project <id>`, and marks the project
FAILED.

Fix `ProjectCheckoutService.setUpLocalRepoAndPush`'s bootstrap branch so the
installer's output ends up as the checkout at the workarea root (run the installer in a
scratch directory next to the workarea and move `<scratch>/<name>` into the reserved
workarea path — the workarea slug can differ from the raw project name, so the
installer cannot simply be pointed at the parent). Also close the other host-dependent
gap on the same path: the installer's `bootstrap.sh` refuses to make the first commit
when git has no committer identity, and unlike the plain-init branch the bootstrap
branch supplies none — export a full git author/committer identity into the
installer's environment. Bring the bootstrap branch under test with a stubbed installer
command honouring the real installer's observable contract, so the engine's side of the
contract cannot regress silently again (third consecutive bootstrap defect after #513
and #519, all shipped through the missing coverage).

## Done when
- With a stubbed installer following the real contract, `setUpLocalRepoAndPush(project,
  true)` produces a git checkout at the workarea root on branch `main`, pushed to
  `origin`, and the project is marked READY — asserted by a new test in
  `ProjectCheckoutServiceTest`.
- The installer invocation carries a full git author/committer identity in its
  environment — asserted by a test.
- A failing installer (non-zero exit), and an installer that exits 0 without producing
  the expected checkout, each mark the project FAILED with a warning log, never throw —
  asserted by tests.
- No scratch/temporary directory is left behind next to the workarea after success or
  failure — asserted by a test.
- `./mvnw -B test` exits 0.
- Human-judged: in a running console, creating a new project with the bootstrap
  checkbox reaches READY, the workarea root is the checkout, and the new GitHub
  repository contains the bootstrap commit.

## Explicitly not
- No change to the t-workflow installer itself (it lives in the template repository;
  its `--name`/`--dir` contract is taken as given).
- No upfront org/name request validation in `ProjectController` — an invalid name still
  surfaces as a FAILED project with the underlying tool's message in the engine log.
- No behaviour change to the plain non-bootstrap path.

## Decisions made along the way
- Run the installer in a scratch directory created *next to* the workarea
  (`Files.createTempDirectory(workarea.getParent(), ".bootstrap-<id>-")`) so the final
  `Files.move` is a same-filesystem rename, then delete the scratch best-effort in a
  `finally` (agent, approved by the issue's Done-when, 2026-09-01).
- Supply the git identity as `GIT_AUTHOR_*`/`GIT_COMMITTER_*` environment variables on
  the installer invocation, always — not only when the host lacks one — so the
  bootstrap commit is deterministic and matches the `locklane <locklane@local>`
  identity the plain-init branch already configures locally (agent, 2026-09-01).
- Verify the installer actually produced `<scratch>/<name>/.git` before moving; an
  installer that exits 0 without building a checkout (the shape of this very defect)
  now fails immediately with its own warning instead of surfacing later as a push
  error (agent, 2026-09-01).
- Test seam: a second package-private constructor substitutes the install *command
  string* (the `bash -c` script text) while keeping the real argument contract
  (`$1` = installer URL, `$2` = project name), so tests exercise the whole bootstrap
  sequence against a local stub honouring the installer's observable contract — no
  network (agent, 2026-09-01).

## Deviations / notes
- Observed, untouched (out of scope): `retry()` on a FAILED bootstrap-created project
  re-runs the plain `clone()` path, not `createRepoAndPush` — for a project whose
  GitHub repo was created but never pushed, that clones an empty repository. Reported
  in the closing report as a proposed follow-up issue.
