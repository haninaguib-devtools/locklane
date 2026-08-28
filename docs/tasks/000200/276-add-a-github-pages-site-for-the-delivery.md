# 276 — Add a GitHub Pages site for the delivery workflow docs
Issue: #276

## Asked
Set up a GitHub Pages site for this repository. Originally scoped to mirror
`docs/workflow.md` (the delivery pipeline); **redefined by the human (2026-08-28)** once
that draft was visible — a product repository's Pages site should describe locklane
itself, not the meta-process it was built under. The site keeps the visual style already
built to match the `t-workflow` template's own Pages site, but its copy is now sourced
only from `client/` and `engine/` (source and comments) and git commit history — not from
`CONSTITUTION.md`, `docs/adr/`, or `docs/workflow.md`. The repo is currently private on
GitHub's free plan, so Pages publishing will not actually go live yet — GitHub Pages
needs either a public repo or a paid plan to serve from a private one. The repo is
expected to go public soon, so the site and its build/deploy config should be built and
committed now even though it can't be verified live until then.

## Done when
- `docs/site/` describes locklane the product — sessions/consoles, the client's
  components, the engine's package structure — grounded in `client/` and `engine/`
  (code, comments, commit history), not in the repository's process docs.
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
- Filling in `README.md`'s one-sentence product description or `CONSTITUTION.md` §4 —
  both stay reserved/placeholder; this site's copy is independently derived from the code,
  not a preview of those pending decisions.

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
- The human then asked why the site was about the delivery pipeline at all — "all I want
  from t-workflow is the css styles" — and redefined the goal: describe locklane itself,
  sourced only from reading `client/` and `engine/` (components, engine packages, code
  comments such as `PtySession`/`SessionRegistry`/`OutputBuffer`'s reattach/buffering
  behavior) and commit history, explicitly not from `CONSTITUTION.md` or `docs/adr/`.
  Rewrote every section of `docs/site/index.html` (hero, principles, how-it-works,
  features, reference) to that brief; dropped the "Open questions" section (`docs/
  workflow.md`-specific, no locklane equivalent) and its now-unused `.open-questions`
  CSS. Kept the t-workflow-derived visual system (header/hero/card components, color
  tokens) unchanged. (human + agent, 2026-08-28)

## Deviations / notes
- none
