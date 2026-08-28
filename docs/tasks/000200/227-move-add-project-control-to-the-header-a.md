# 227 — Move add-project control to the header and add a zero-project empty state
Issue: #227

## Asked
Relocate the sidenav's "+ add project" control into the app header/topbar (next to the
account menu), and replace the overview page's zero-projects state — which currently
reuses the "select a project to begin" copy meant for zero-*selection* — with a dedicated
first-run screen: no stat tiles, a centered "+ Add a project" call-to-action, and a quiet
generated backdrop (soft accent-toned gradient + dot grid, not a stock image) consistent
with the app's flat, image-free visual style today.

A comparison mockup built from the app's real colors/components was reviewed and the
header placement + backdrop treatment were approved:
https://claude.ai/code/artifact/63ded136-e4fb-49fc-b31d-ad71eec6be50

## Done when
- The "+ add project" button renders in the topbar (`app.component.html`/`.css`), opens
  the same `app-add-project-popup` modal it opens today, and no longer appears in the
  sidenav's control row.
- `overview.component.*`: when the workspace has zero projects, the page renders a
  dedicated empty state (heading, short subtext, "+ Add a project" CTA that opens the
  add-project popup) instead of the stat tiles or the "select a project to begin" text —
  checked by hand against a workspace with zero projects.
- The empty-state backdrop is CSS only (gradient + generated pattern) — no image asset is
  added to the repo.
- The existing "no project selected" state (workspace already has ≥1 project) is
  unchanged.

## Explicitly not
- Redesigning the usage widget or its position.
- Changing the add-project popup's own fields/content.
- Any change to the sidenav's "no issues" empty text.

## Decisions made along the way
- Popup ownership (`showAddProject` state + `<app-add-project-popup>`) moves from
  `SidenavComponent` up to `AppComponent`, since it now has two openers (the header
  button and the overview's zero-state CTA) and the popup itself is a fixed full-screen
  overlay, so its position in the DOM tree doesn't affect layout (hani, 2026-08-27).
- `OverviewComponent` gains an `addProject` output (emitted by its zero-state CTA) and a
  public `refresh()` method; `AppComponent` calls both `SidenavComponent.refresh()` and
  `OverviewComponent.refresh()` (via `@ViewChild`) after a project is created, so both
  views pick up the new project without a page reload (hani, 2026-08-27).
- Header button placement and empty-state copy/layout follow "Option A — header" and the
  "Proposed" zero-project panel from the approved mockup verbatim (button before the
  avatar in `.topbar-actions`; heading "No projects yet", subtext "Add a repository to
  start tracking its issues here.", CTA "+ Add a project").

## Deviations / notes
- none
