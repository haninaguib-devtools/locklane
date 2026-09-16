# 957 — Fix JetBrains Gateway remote-SSH open failing with underspecified deploy params
Issue: #957

## Asked
Opening a worktree in JetBrains Gateway (remote SSH), added by #949/#950, fails with
"Cannot Connect: There was an error in the connection provider" whenever
`locklane.remote-ide.gateway.*` is left at its default blank config -- the VS Code
(remote SSH) entry works fine in the same setup. The cause: with `product-code`,
`build-number` and `ide-path` all blank, `jetbrainsGatewayUrl()`
(`client/src/app/services/remote-ide-link.ts`) builds a `jetbrains-gateway://connect`
link with `deploy=true` and none of `productCode`/`buildNumber`/`idePath` set. The
code's own comment assumed Gateway would then prompt which IDE to deploy, but current
Gateway versions instead refuse the connection outright with that exact error
(JetBrains YouTrack GTW-6264, "Cannot connect due to underspecified deploy parameters
for the SSH connector"). Guard the click so an unconfigured Gateway shows an
operator-facing hint pointing at the settings to configure, instead of opening a link
already known to fail, and correct the now-wrong "Gateway asks which IDE" comments in
`remote-ide-link.ts` and `application.yml`.

## Done when
- Choosing "JetBrains Gateway (remote SSH)" with `locklane.remote-ide.gateway.*` unset
  shows a hint naming `ide-path` (or `product-code` + `build-number`) instead of
  opening a `jetbrains-gateway://` link.
- Choosing it with `ide-path` set, or with both `product-code` and `build-number` set,
  still opens the link exactly as before.
- The VS Code (remote SSH) entry is unaffected.
- `remote-ide-link.ts`'s and `application.yml`'s comments describe the actual Gateway
  behavior, not the "Gateway asks which IDE" assumption disproven by GTW-6264.

## Explicitly not
- Auto-detecting an installed Gateway backend on the engine host to fill `ide-path` in
  automatically -- ruled out by #949, same as before.
- A product/build picker in the UI -- ruled out by #949, same as before.
- Fixing the underlying Gateway/JetBrains bug itself -- out of this repo's control.

## Decisions made along the way
- Added `gatewayLinkIsUsable(link)` and `gatewayUnconfiguredHint()` to
  `remote-ide-link.ts` rather than changing `jetbrainsGatewayUrl()` itself: the
  function's `deploy=true`-alone output still matches JetBrains' documented link
  format, it is just a link Gateway happens to refuse today, so the guard belongs at
  the call site (before opening the link), not in the link builder.
- Left `REMOTE_IDES`/the Settings picker unchanged: filtering out the Gateway entry
  when unconfigured would need fetching `gateway` config before the picker renders,
  which is more surface than this bug needs -- the click-time hint is enough and
  matches the existing `ideOpenFailed`/`remoteIdeHint` pattern already in both
  components.

## Deviations / notes
- Diagnosis and the fix were drafted before this task's issue existed (same
  conversation, same worktree) -- caught mid-task by the human, who had the issue
  opened retroactively (#957) before driving continued. No file outside the
  issue's Scope was touched.

## Agents
- work: claude-code / claude-sonnet-5
