# 775 — Keyboard shortcuts for back and forward navigation
Issue: #775

## Asked
In the installed (PWA) app there is no browser chrome, so once you have navigated from
an issue page to a project page to a console there is no way to retrace your steps
except through the sidenav. Add keyboard shortcuts that walk the app's own history back
and forward, exactly as the browser's back and forward buttons do — the same chords
browsers already use: `Alt+Left` / `Alt+Right` on Windows and Linux; `Cmd+[` /
`Cmd+]` and `Cmd+Left` / `Cmd+Right` on macOS. They also work as a shortcut in a normal
browser tab.

## Done when
- With focus on the page body, `Alt+Left` (non-mac) / `Cmd+[` and `Cmd+Left` (mac)
  returns to the previous app route, and `Alt+Right` / `Cmd+]` / `Cmd+Right` moves
  forward again; the keydown event is `defaultPrevented`.
- With focus inside a console terminal, `Alt+Left` on a non-mac platform is not
  intercepted; the same holds for focus in an `input`, `textarea`, or `contenteditable`
  element.
- On macOS `Cmd+[` with focus inside a terminal still navigates back.
- Unit tests in `client/src/app/app.component.spec.ts` cover: back and forward on each
  platform's chords, the terminal / editable-field exclusion, the wrong-platform chord
  being ignored, and extra modifiers being ignored.
- `./mvnw -B test` passes (client build inputs changed, so the check runs).

## Explicitly not
- No change to the terminal component's own key handler; word-jump in a shell stays as
  it is.
- No mouse back/forward buttons — they already work.
- No configurable keybindings and no shortcut-help UI.
- No `CHANGELOG.md` edit here: release notes are generated from squash commits at cut
  time.

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- The terminal-focus exclusion (`.xterm` / `app-terminal`) is applied only for the
  non-mac Alt chord, not for the mac Cmd chord — matching the issue's own explicit
  done-when ("On macOS `Cmd+[` with focus inside a terminal still navigates back") and
  its design note that xterm.js never consumes Cmd combinations, so there is no
  word-jump conflict to guard against on that platform. The editable-field exclusion
  (`input`/`textarea`/`[contenteditable]`) applies on both platforms. (Claude,
  2026-09-07)
- `isMacPlatform()` is a `protected` instance method rather than a free function, so
  `/t-work`'s own unit tests can deterministically stub the platform per test
  (`spyOn`) instead of depending on the real browser running the test suite. (Claude,
  2026-09-07)

## Deviations / notes
- none
