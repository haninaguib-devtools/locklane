# 463 — Keep -SNAPSHOT on main; strip it at release cut
Issue: #463 · Part of: #462

## Asked
Make "what version is `main` building toward" and "what version got released"
mechanically distinct. `pom.xml`'s `<revision>` stays `X.Y.Z-SNAPSHOT` on `main` at all
times, so a non-release build is identifiable by its version string. The maintainer
chooses the next version — major, minor, or patch — by editing that one line through an
ordinary PR whenever they decide, ahead of the cut. The manually dispatched release
workflow (`.github/workflows/release.yml`) derives the release version by stripping
`-SNAPSHOT` from the current revision, and tags/publishes the permanent `vX.Y.Z` release
from it. `docs/architecture/releasing.md` is amended to describe this flow; while in
there, its stale claim that releases happen "on every push to main" is fixed — the
workflow has been `workflow_dispatch`-only since task 207 (PR #210).

## Done when
- `<revision>` on `main` ends in `-SNAPSHOT`.
- Dispatching `release.yml` while `<revision>` is `X.Y.Z-SNAPSHOT` produces a permanent
  release tagged `vX.Y.Z`.
- Dispatching it again for a version whose tag already exists fails without touching the
  existing release (immutability rule in `docs/architecture/releasing.md` §Immutability
  holds).
- `docs/architecture/releasing.md` describes the actual trigger (manual dispatch) and the
  `-SNAPSHOT` convention; `grep -i "every push" docs/architecture/releasing.md` finds
  nothing.

## Explicitly not
- No automatic version bumping of any kind — the bump is always the human's explicit
  edit.
- No change to what artifacts a release contains.
- The rolling `latest` release logic stays as-is — retiring it is #465.
- Release notes / body content beyond the minimal existing wording — curated notes are
  #464 (blocked by this task).

## Decisions made along the way
- Build the release with `-Drevision=<stripped version>` so the published jar itself
  carries the bare release version, not `-SNAPSHOT`; Maven's native CI-friendly versions
  resolve the override reactor-wide, per the root `pom.xml` #97 comment (agent, 2026-08-31,
  pinned in the issue's Plan).
- Strip via shell `${version%-SNAPSHOT}` — a no-op when the suffix is already absent, so
  a dispatch against a bare `<revision>` (convention violated elsewhere) still cuts that
  version rather than failing on a formality; the immutability guard is what protects
  released versions (agent, 2026-08-31).
- The permanent-release step is unconditional on dispatch; the immutability check (fail
  loudly when `v<version>` already exists) is kept verbatim in spirit and moved onto the
  derived version (agent, 2026-08-31).
- `docs/architecture/releasing.md` gains a `## Immutability` heading because the issue's
  done-when cites "§Immutability" and the rule previously lived in unheaded prose
  (agent, 2026-08-31).

## Deviations / notes
- Driven-run base: this task is a child of initiative #462 driven by `/t-drive 462`
  (ADR-004). The branch was created by the driving session from `wip/462-integration`,
  not `main`, and the draft PR targets `wip/462-integration`; the PR body carries no
  auto-close phrase — the initiative's single aggregate PR to `main` closes #463 later.
  Phase 1's rebase-onto-`origin/main` step was skipped on the driving session's explicit
  direction (integration base is the design, and `wip/462-integration` is currently at
  `main`'s tip anyway).
- `pom.xml` needed no change: `<revision>` is already `0.1.0-SNAPSHOT`. The Plan listed
  it defensively because the issue's Scope names it.
- `.t-workflow/scripts/template-owned-paths.sh --list` names both
  `.github/workflows/release.yml` and `docs/architecture/releasing.md`, but the pinned
  consumer manifest (`.template-manifest.json`, the record `check-manifest.sh` actually
  verifies) lists neither — they are locklane-authored files (tasks #95/#98/#207), so no
  `<!-- local -->` slot constraint applies. `check-manifest.sh` run after the edits to
  prove no owned file drifted.
- Ride-along comment fix in `release.yml` (in-scope file): the build step's old comment
  claimed `engine/pom.xml` "parents to spring-boot-starter-parent rather than the root
  aggregator" — it actually parents to the root aggregator (`dev.locklane:locklane`,
  version `${revision}`). The comment now states the still-true rationale (the
  client-module reactor dependency) without the false claim.
