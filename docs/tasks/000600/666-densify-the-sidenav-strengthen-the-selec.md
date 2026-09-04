# 666 — Densify the sidenav, strengthen the selected row, box the expand/collapse toggle
Issue: #666

## Asked
Make the sidenav denser and make the selected entry unmistakable, and give the
expand/collapse toggle a proper control look instead of a bare text glyph. Roughly a
third more rows fit in the same height, the selected row is filled with the
accent-soft tone with a 3px accent rail and semibold accent-ink text, and every twisty
is a small bordered square with a chevron that rotates when open. Font sizes stay
where they are; only spacing, fills, and the toggle change. Implements "Toggle A" and
"Selection A" from the reference mockup.

## Done when
- `.row` and `.section-header` in `sidenav.component.css` declare `height: 22px` and
  `height: 26px` respectively.
- `.row.active` no longer declares `border-left`; only `ul.branch`'s guide does
  (`grep -c "border-left"` == 1). It declares `background: var(--accent-soft)` and an
  `inset 3px` box-shadow instead.
- `--accent-ink` is declared in `client/src/styles.css` `:root`.
- The `▸` character no longer appears in `sidenav.component.html`; both twisties
  render an inline SVG chevron inside `button.twist`.
- `env -u GH_TOKEN -u GIT_CONFIG_COUNT ./mvnw -B test` passes, including the sidenav
  spec's twisty, kebab, and `.section-header.active` tests.
- Human judgment: side by side with the mockup's "Proposed" pane at 260px sidebar
  width, the running app's sidenav matches it in row density, selected-row treatment,
  and toggle look; project drag handle, pop-out, new-console, console-dot, and kebab
  controls still line up on their rows and remain clickable.

## Explicitly not
- No change to the sidenav's behaviour, data, filtering, pinning, or drag-and-drop;
  class names the spec selects on stay as they are.
- No change to the Shells window sidenav (`shells-sidenav`), the topbar, or the
  sidebar resizer.
- No collapse-the-whole-sidebar control.

## Decisions made along the way
- none

## Deviations / notes
- none
