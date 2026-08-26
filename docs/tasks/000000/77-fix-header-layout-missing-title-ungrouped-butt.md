# 77 — Fix header layout: missing title, ungrouped buttons, too short
Issue: #77

## Asked
The top header bar (client/src/app/app.component.html) is missing an app title on the left, and its two right-side controls (the consoles button and the Log out button) are not visually grouped together. The header is also noticeably shorter than the header in comparable apps in this workspace (e.g. portstow's frontend uses a 60px-tall header); this one should match that height.

## Done when
- The header shows a "LockLane" title on the left.
- The consoles button and the Log out button sit together as a group on the right side of the header.
- The header height matches other apps' header height (60px, matching portstow's `--header-h`).
- Existing header functionality (opening the consoles picker, logging out) still works.

## Explicitly not
none

## Decisions made along the way
- none

## Deviations / notes
- none
