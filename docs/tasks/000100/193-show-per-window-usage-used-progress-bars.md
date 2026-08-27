# 193 — Show per-window usage-used progress bars in the usage widget
Issue: #193

## Asked
The usage widget's expanded panel (`client/src/app/components/usage-widget/`)
currently draws one progress bar per provider (Claude, Codex) driven only by the
5-hour window; the weekly window is a plain text line ("weekly X% left · resets
..."). Redesign the expanded panel so every window gets its own row, styled after
Anthropic's usage-settings page: a label on the left ("5-hour limit" / "Weekly"), a
reset time and a percentage on the right, and a full-width progress bar underneath.
Every percentage the widget shows — bar fill and label — switches from "percent
left" to "percent used" to match that reference.

## Done when
- The expanded panel renders one row per window (5-hour, weekly) per provider that
  has data: a label, "resets in/at …" text, a percent-used number, and a full-width
  bar filled to that percent.
- Bar fill and displayed percentage reflect usage consumed (`100 - percentLeft`),
  not `percentLeft`.
- A provider with no weekly data (`weekly === null`) still shows only its 5-hour
  row, same as today.
- `usage-widget.component.spec.ts` covers the used-percent calculation and renders
  both rows when weekly data is present.
- `./mvnw -B test` and `./scripts/consistency-check.sh` pass.

## Explicitly not
- No model/effort shown anywhere in the widget — deferred until the engine tracks
  which model/effort a session used.
- No change to the collapsed row's layout.
- No per-model weekly breakdown row — the engine only tracks 5-hour and weekly
  totals per provider.

## Decisions made along the way
- none

## Deviations / notes
- none
