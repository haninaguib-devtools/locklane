# 574 — Make the console PTY size follow the focused client
Issue: #574

## Asked
Inside a project console, the shell sometimes believes the screen is a few columns
wider than it really is, so the CLI's prompt box border (`───`) wraps onto a second
row and typing lands in a broken box. The engine's PTY size was whatever the *last*
attached browser client sent, so any other window attached to the same session
overwrote the size of the window the user was actually typing in. Make the PTY size
follow the focused client, and make the browser resend its true size whenever it
might have gone stale.

## Done when
- Engine applies a resize frame only from the attachment currently marked focused;
  a resize from an unfocused attachment is remembered and applied when that
  attachment becomes focused; focus moving to another attachment applies its last
  known size. A single attachment behaves as before.
- Client refits and resends the size when the browser tab regains
  focus/visibility, and once after the initial layout settles.
- Unit tests cover two attachments with different sizes and focus switching; the
  client resending size on foreground.
- `./mvnw -B test` and `./.t-workflow/scripts/consistency-check.sh` pass.

## Explicitly not
- No change to the sidenav or its version footer — split to #575.
- No change to the wire protocol frame format.

## Decisions made along the way
- The focus/size arbitration lives in the WebSocket handler (a new
  `AttachmentSizeArbiter`), keyed by WebSocket session id, rather than in
  `PtySession` — the PTY has no notion of attachments and the handler is the only
  place that does (agent, 2026-09-02).
- While no attachment has reported focus, every resize applies, exactly as before,
  so a lone client (or one that mounted inactive) never waits on anyone
  (agent, 2026-09-02).
- The client's foreground handler now also sends a focus frame for the selected
  tab, not only a connection check: without it the engine could not know which
  window came back, since focus was previously reported only on open and on an
  in-app tab switch (agent, 2026-09-02).

## Deviations / notes

- `./mvnw -B test` on this machine fails three engine tests that also fail on a
  clean `origin/main` checkout (`ProjectCheckoutServiceTest.createProjectWithoutAnAccountConfiguresNoCredentialHelper`,
  `ProjectWorktreesServiceTest.listIncludesACleanDetachedProjectConsoleWorktreeWithNoIssueNumber`,
  `WorktreeCleanupSweeperTest.sweepLeavesAProjectConsoleWorktreeAloneWhileItsSessionIsLive`)
  — a host git config (`credential.helper=osxkeychain`) and live worktree state, not
  this change. Everything else, including the new tests, passes; CI is the clean run.
