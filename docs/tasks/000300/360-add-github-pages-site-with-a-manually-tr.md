# 360 — Add GitHub Pages site with a manually-triggered deploy workflow
Issue: #360

## Asked
Give the project a small static website, served by GitHub Pages, whose source lives in
a new `site/` directory at the repository root. The site's own files (`index.html` and
whatever else it needs) will be supplied separately — this task's job is to stand up
the directory and the deployment mechanism: a GitHub Actions workflow that a human can
trigger by hand from the Actions tab to publish whatever is in `site/` to GitHub Pages.
The workflow deploys on-demand only; it never runs automatically on a push.

## Done when
- A `site/` directory exists at the repository root (a placeholder file is acceptable
  until real content is supplied).
- A workflow file under `.github/workflows/` deploys the contents of `site/` to GitHub
  Pages, triggered only by `workflow_dispatch` — no `push` or other automatic trigger.
- The workflow uses the standard GitHub Pages deploy actions (`actions/configure-pages`,
  `actions/upload-pages-artifact`, `actions/deploy-pages`) with `site/` as the artifact
  root.
- Running the workflow manually from the Actions tab succeeds and publishes the page
  (human-judged — requires GitHub Pages enabled for this repository with source
  "GitHub Actions").

## Explicitly not
- Authoring the actual site content (`index.html` and any other static files) —
  supplied by the human, not part of this task's diff beyond a placeholder.
- Automatic deployment on push to `main` or any other branch — deploys are manual only,
  by design.

## Decisions made along the way
- Workflow file named `.github/workflows/pages.yml`, matching the name
  `docs/architecture/manifest.md` §Which files are template-owned already lists in the
  template's genesis-only exclusion set alongside `site/` — this repo owns the file
  outright, no `local-slots.md` region applies (haninaguib, 2026-08-29, via `/t-plan`).

## Deviations / notes
- none
