# 153 — Move the latest tag to the released commit on each rolling release
Issue: #153

## Asked
The rolling `latest` release publishes a fresh jar on every push to main, but the
`latest` git tag never moves after its first creation: the release page permanently
shows the commit the tag was first created on, and `git checkout latest` yields stale
source. Make each successful release run move the `latest` tag to the commit it just
built, so the release page and the tag always match the jar.

## Done when
- After a push to main whose Release workflow succeeds, `git ls-remote origin
  refs/tags/latest` returns that push's commit SHA.
- `gh release view latest --json targetCommitish` (or the release page) reflects the
  newest released commit.
- The jar download URL for the `latest` release never 404s during the update (update in
  place, no delete-and-recreate of the release).

## Explicitly not
- No change to permanent v* releases — their tags are immutable by design.
- No fix for the flaky EventsWebSocketHandlerIntegrationTest that blocked a prior run;
  that is separate work.

## Decisions made along the way
- Move the tag with plain git (`git tag -f latest "$GITHUB_SHA"` then
  `git push --force origin refs/tags/latest`) rather than `gh release edit --target`:
  GitHub's release API only applies `target_commitish` when a tag is first created, so
  editing an existing release's target never moves the tag. (haninaguib, 2026-08-26, per
  plan on issue #153)

## Deviations / notes
- none
