# 276 — Add a GitHub Pages site for the delivery workflow docs
Issue: #276

## Asked
Set up a GitHub Pages site for this repository that presents the delivery-system
documentation in a browsable site, organized in the same style/shape as the existing
`docs/workflow.md` overview (principles, the pipeline, task identity, etc.). The repo is
currently private on GitHub's free plan, so Pages publishing will not actually go live
yet — GitHub Pages needs either a public repo or a paid plan to serve from a private one.
The repo is expected to go public soon, so the site and its build/deploy config should be
built and committed now even though it can't be verified live until then.

## Done when
- A source for the site exists in the repo (`docs/site/`), mirroring `docs/workflow.md`'s
  structure and content rather than inventing new content.
- A Pages build/deploy configuration exists (`.github/workflows/pages.yml`, using
  `actions/deploy-pages`) that would deploy correctly once the repository is public — a
  human can judge this by reading the config, since it cannot be exercised live yet.
- The task record and PR description explicitly note that live publishing was not (and
  could not be) verified, and why (private repo on the free plan).

## Explicitly not
- Making the repository public — that's a separate action for the human to take.
- Verifying the live published site — blocked on the repo going public. **This means
  the Pages workflow below has never actually run.** Its correctness was checked by
  reading it against GitHub's documented `actions/deploy-pages` setup and by validating
  its YAML syntax and required permissions, not by a live deploy — the earliest that can
  happen is after the repository goes public.

## Decisions made along the way
- Site source lives at `docs/site/`, built as plain static HTML with no Jekyll/build
  step, published via `actions/upload-pages-artifact` pointed at `docs/site` — never
  GitHub Pages' "deploy from a branch, serve `/docs`" mode, which would publish the rest
  of `docs/` (ADRs, architecture, task records) unfiltered. (agent, 2026-08-28, per the
  plan's Risks/constraints.)
- The three GitHub Pages actions (`configure-pages`, `upload-pages-artifact`,
  `deploy-pages`) are pinned to commit SHAs resolved via the GitHub API at plan time,
  matching the pinning style already used in `ci.yml`. (agent, 2026-08-28)
- After the first draft PR, the human asked for the site's visual style to match the
  `t-workflow` template repository's own GitHub Pages site
  (`haninaguib-devtools/t-workflow`, live at
  `https://haninaguib-devtools.github.io/t-workflow/`) rather than the generic sidebar
  layout first drafted. Reworked `docs/site/` to reuse that site's actual design tokens,
  header/hero/button/card components, and brand mark (fetched its live HTML/CSS/JS to
  copy the design system, trimmed to the components this page uses), while keeping the
  page's own content a mirror of `docs/workflow.md` — t-workflow's page is
  installer/marketing copy specific to that template project, which does not apply here,
  so only the visual language was reused, not that copy. (human + agent, 2026-08-28)

## Deviations / notes
- none
