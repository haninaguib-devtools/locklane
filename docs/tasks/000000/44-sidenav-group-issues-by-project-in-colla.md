# 44 — Sidenav: group issues by project in collapsible sections
Issue: #44 · Part of: #41

## Asked
Replace the sidenav's single "CASES" heading with one collapsible section per added
project, each showing that project's issues fetched via the now project-scoped API.
Pinned issues stay pinned at the top of the sidenav but are grouped by project
underneath the pinned block. All projects are shown at once (not a single-project
switcher).

## Done when
- Sidenav renders one collapsible section per project the user has added.
- Each section lists that project's issues via the project-scoped API.
- Pinned issues appear in a top pinned block, grouped by project.
- Collapsing/expanding a project section works and persists per session.

## Explicitly not
- The cross-project client-side filter UI — decided to need no dedicated task
  (trivial show/hide once sections exist).
- Add Project popup/flow — #45.

## Decisions made along the way
- Every prior task (#42/#43) assumed exactly one "current" project (the one in the
  URL). This is the first place the app actually shows more than one project at
  once, so it's also the first place two client-only stores need a project
  dimension they never had:
  - `PinStore`: the issue itself already says pins are "grouped by project" in the
    pinned block, so a pin is now a `(projectId, issueNumber)` pair, not a bare
    issue number. `localStorage`'s stored shape changes; existing pins reset once
    (client-only convenience state, never synced anywhere, no migration written —
    haninaguib, 2026-08-26).
  - `CollapseStore` (per-initiative fold): not explicitly called out in the
    done-when, but left bare-`issueNumber`-keyed it has the identical cross-project
    bleed problem the pin fix addresses — folding initiative #1 in one project's
    section would also fold "#1" in every other section. Given the same project id
    dimension either way, keyed it the same way as pins rather than leaving a
    known-identical bug unfixed next to its fix (haninaguib, 2026-08-26).
  - A new store, `ProjectSectionStore`, holds the done-when's actual new
    requirement — whole-section collapse, keyed by bare `projectId` (a different
    dimension than folding one initiative within a section).
- `SidenavComponent` no longer takes a `projectId` input — it fetches its own
  project list (`ProjectsService`, already built for #43's default-project guard)
  and, per project, its own issue tree. It stops depending on "the current URL's
  project" entirely; that concept still exists for `MainContentComponent` (which
  issue's console is open) but no longer constrains what the sidenav shows.
- `selectedChange`/`selected` on `SidenavComponent` change shape from a bare issue
  number to `{ projectId, issueNumber }` — selecting an issue in any section must
  say which project it belongs to, and highlighting the active row needs the same
  pair to avoid highlighting same-numbered issues across sections.
- The single filter bar (text + hide-shipped) stays global across every section
  rather than becoming per-section — the issue's Non-goals only excludes a
  cross-*project* filter (show/hide whole sections), not this existing filter,
  and duplicating a text box per section was never asked for.

## Deviations / notes
- Manually verified in a real browser, not just the test suite: ran the engine on
  an isolated port/data-dir (so as not to touch the human's own already-running
  dev instance on 8080/4200) and `ng serve` proxied to it. Logged in as the
  bootstrap admin, confirmed against the real, already-open GitHub issues:
  one collapsible section per project (only one project existed, "locklane"),
  collapsing/expanding the section works, pinning issue #44 from its row menu
  moved it into a top "pinned" block grouped by the project name, removed from
  the section below, and selecting an issue navigated to and rendered its real
  detail/flow-state (haninaguib, 2026-08-26).
