# 428 — Tint project pages with a lighter version of the project's accent color
Issue: #428 · Part of: #426

## Asked
Give each project's pages a subtle background tint derived from that project's accent
color (added on the backend in #427), so a person gets a background sense of which
project they're in without it being visually loud. The existing global accent setting
(`AccentThemeStore`, chosen in the Appearance section of the settings dialog) is
unaffected in scope: it continues to drive chrome that is not project-specific — the
navbar, header, and anything shown outside a project's own pages.

## Done when
- A project's owner can set that project's accent color from the client (a picker in
  the project's own settings, not the global Appearance section, which stays global).
- While viewing any page inside a project that has an accent color set, the page
  background is visibly tinted with a light, low-contrast derivative of that color
  (readable body text and existing UI contrast are preserved — this is a background
  wash, not a saturated fill).
- Two projects with different accent colors are visually distinguishable by background
  tint alone when switching between them.
- A project with no accent color set (including every project that pre-dates this
  feature, since the backend column is nullable) falls back to today's neutral
  background — no visual regression for existing projects.
- The navbar/header continue to use the existing global `--accent` / `--accent-soft`
  variables and do not change when a project's own accent color changes.
- The derived tint is computed client-side from the project's stored accent color (no
  new backend field beyond the raw color from #427).

## Explicitly not
- Any backend change — depends on the accent color field and API added in #427.
- Changing the global accent setting's behavior or storage.

## Decisions made along the way
- This task's blocker, #427, was merged into this initiative's integration branch
  (`wip/426-integration`) earlier in this same `/t-drive` run, but its tracker issue
  stays open by design until the initiative's single aggregate PR closes every included
  child (`AGENTS.md`/ADR-004 Decision 3 — a driven run's per-child merges are not
  `main`-bound, so they carry no `Closes` line of their own). `/t-work`'s own literal
  blocker-gate check (`tracker:list-blockers` → `check-blocker-gate.sh`) would therefore
  read #427 as an unresolved, still-open blocker and refuse. Per `/t-drive`'s own Phase 2
  step 1 ("blocked by another child of this initiative, not yet resolved → hold; revisit
  once that child's outcome — merged or excluded — is known"), the blocker is treated as
  satisfied once #427 was merged, superseding the literal gate result for this
  intra-initiative case; this matches this repository's own precedent (initiative #236:
  child #239, `blockedBy` #238, was worked and merged while #238's issue was still open,
  both closing together only at the aggregate PR). Decided by the driving session
  (2026-08-30) rather than stopping the run — flagged here for visibility rather than
  silently bypassed.
- The accent-color picker (in `ProjectSummaryComponent`, the project's own settings
  page) reuses the four existing global presets (`ACCENT_PRESETS` in
  `accent-theme-store.ts`) as swatches, submitting each preset's raw `accent` hex value
  to #427's endpoint rather than inventing a second, project-specific preset list or a
  free-form color input — the issue left the exact picker UI to implementation, and
  this keeps the two accent pickers visually consistent.
- `client/src/app/models/issue.model.ts`'s `Project` interface gained an
  `accentColor: string | null` field mirroring the backend's `ProjectView` (#427). This
  file isn't itself named in the issue's Scope, but every consumer of `Project` needed
  it to read/derive the tint, and it is a straight mirror of `ProjectView`'s own new
  field, not a design decision of its own.

## Deviations / notes
- Adding a required field to `Project` means every existing fixture literal typed as
  `Project` needed it too, or the build fails to compile — six otherwise-unrelated spec
  files (`app.component.spec.ts`, `add-project-popup.component.spec.ts`,
  `console-indicator.component.spec.ts`, `main-content.component.spec.ts`,
  `overview.component.spec.ts`, `sidenav.component.spec.ts`) each gained one
  `accentColor: null,` line in their existing `Project` fixtures — no behavior in any
  of those components' own tests changed.
