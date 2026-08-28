# 258 — Add brand logo and restyle console indicator in the header
Issue: #258

## Asked
The app header currently shows only the plain-text "LockLane" title, and the "consoles"
status button next to it is a square-cornered badge with no visual status marker. Add the
Locklane padlock/worktree logo to the left of the "LockLane" title, and restyle the
console indicator into a rounded pill with a small status dot, so the header reads as a
cohesive branded bar.

## Done when
- The Locklane SVG mark (padlock body in `--accent`, shackle/worktree strokes in
  `--text`) renders to the left of the "LockLane" brand link in
  `client/src/app/app.component.html`, sized and spaced to match the mock (~24px icon,
  ~9px gap), as a sibling in a small flex group — the existing `routerLink="/"` behavior
  on the brand link is unchanged.
- The SVG asset lives under `client/public/` and is referenced from the template, not
  inlined as a giant literal block, unless inlining is the cleaner Angular idiom for a
  two-color icon at this size — implementer's call.
- The console indicator badge is restyled to a fully-rounded pill (not the current 4px
  corner radius) with a small status dot rendered before its label text.
- The dot's color continues to track the badge's existing state logic: `--green` (or the
  existing default badge color) when idle, `--amber` matching the current `.waiting`
  pulse state when `hasWaitingEntry()` is true — no new state introduced.
- `cd client && CHROME_BIN=... npx ng test --watch=false` passes, including
  `console-indicator.component.spec.ts`.
- Manually verified in a running instance: header shows logo + "LockLane", console badge
  shows as a pill with a dot in both idle and waiting states.

## Explicitly not
- No change to the "+ add project" button or account/avatar menu styling.
- No change to the login page's "locklane" heading.
- No change to the console picker dialog (`.picker`, `.entry`, etc.) beyond the trigger
  badge itself.

## Decisions made along the way
- No approved mock image was available in this session; the SVG mark's exact shape
  (padlock body + shackle, arc-and-rounded-rect two-color icon) was designed to satisfy
  the issue's literal color/sizing spec (Hani, 2026-08-27).
- Referenced the logo as an external `<img>` asset under `client/public/` rather than
  inlining: the app has no dark-mode/theme switching (single fixed palette in
  `styles.css`), so a static two-color SVG with hardcoded hex values matching
  `--accent`/`--text` needs no live CSS-variable binding (Hani, 2026-08-27).

## Deviations / notes
- none
