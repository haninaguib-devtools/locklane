# 346 — Move ADR-007 reserved-surfaces note out of CONSTITUTION.md's template-owned region

Issue: #346

## Asked
The template sync (`/t-update`) refuses to run: `CONSTITUTION.md` no longer matches the
v0.0.3 pin recorded in `.template-manifest.json`. Task #236 (PR #345) replaced §3's
short reserved placeholder with a note, per ADR-007, describing the
tenancy/authorization surfaces that will become protected once implemented — but that
paragraph sits in the template-owned region of the file, outside the `<!-- local -->`
slot, so `check-manifest.sh` reports drift and every future sync is blocked. The
template repo (t-workflow) must not change, so the fix is entirely in locklane: restore
§3's reserved paragraph to the exact v0.0.3 template text, and keep the ADR-007 note's
substance in locklane-owned territory — inside `CONSTITUTION.md` §4's
`<!-- local -->`…`<!-- /local -->` slot (which sync copies forward unchanged, per
`docs/architecture/local-slots.md`), keeping §2.3's "operative rule lives in this file"
requirement satisfied, with the full rationale remaining in ADR-007 itself.

## Done when
- The §3 reserved paragraph is byte-identical to the v0.0.3 template text:
  `*(reserved: application surfaces — data-privacy paths, contracts, migrations,
  grants, audit — to be added when the application exists.)*`
- The ADR-007 note's substance (which surfaces become protected, why they are not yet
  backticked into §3 or `protected-paths.sh`, and that tasks #238–#242 add each bullet
  and its `protected-paths.sh` pattern together) is preserved inside the
  `<!-- local -->` region of §4, or in ADR-007 with a one-line §4 pointer.
- `./.t-workflow/scripts/check-manifest.sh` exits 0 (no DRIFT lines).
- `./.t-workflow/scripts/consistency-check.sh` exits 0.

## Explicitly not
- No change to the t-workflow template repo — explicitly ruled out by the owner; the
  fix happens in locklane only.
- Not the v0.0.3 → v0.0.4 sync itself: once this merges, `/t-update` is re-run and
  opens its own task per its own procedure.
- No change to `.t-workflow/scripts/protected-paths.sh` or to what is actually
  protected — the ADR-007 surfaces stay reserved-not-yet-enforced, exactly as today.

## Decisions made along the way
- The note lands in §4's `<!-- local -->` slot as a new numbered item pointing at
  ADR-007, not as an edit to ADR-007 itself: ADRs are append-only (§2.1), ADR-007's
  Context and Consequences already carry the full rationale, and §2.3 wants the
  operative rule in the constitution. Decided at plan time (owner-invoked `/t-plan`,
  2026-08-29); the issue's done-when allowed either placement.
- §3 is restored verbatim from the pre-#236 revision (`c5792df~1`), whose normalized
  hash was verified at plan time to equal the manifest's expected
  `15be69adfcc284806908824522b439918f887158a83a0317994a64f5ea288803` — no
  reconstruction of the template text by hand. (agent, 2026-08-29)

## Deviations / notes
- none
