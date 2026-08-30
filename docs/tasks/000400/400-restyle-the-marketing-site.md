# 400 — Restyle the marketing site
Issue: #400

## Asked
Give the public landing page a fresh visual style. The page at `site/index.html` keeps
its current content and structure; what changes is how it looks — colours, typography,
spacing, and the styling of its existing sections. The maintainer directs and hand-edits
the new look directly in the stylesheet and markup; this task exists to give that work a
branch, a record, and a reviewed path to `main` rather than an untracked edit.

## Done when
- `site/index.html` and `site/styles.css` carry the new style, and the page renders
  without regressions when opened in a browser (all existing sections present, no broken
  layout at desktop and mobile widths).
- `./mvnw -B test` and `./.t-workflow/scripts/consistency-check.sh` pass.
- The maintainer judges the new look correct — human judgment by design, since the target
  style is set by hand during the work rather than specified up front.

## Explicitly not
- The console application's own UI under `client/` — a different surface with its own
  theming (including the accent-colour setting), not touched here.
- New page content, copy rewrites, or new sections: this is a restyle of what is already
  on the page.
- Any engine or build change.

## Decisions made along the way
- The styling itself is the maintainer's hand-edit, not the agent's (maintainer,
  2026-08-30). The agent's part of this task is the branch, this record, the checks, and
  the draft PR; it does not author or alter `site/index.html`, `site/styles.css`, or the
  assets beside them.

## Deviations / notes
- Gates at the start of the work: no blockers on #400; the scope (`site/` only) is not a
  protected surface, so no `## Plan` was required. Branch created from a current
  `origin/main` at 0da243e.
