#!/usr/bin/env bash
# Cross-artifact consistency check: mechanizes the cheap, high-precision half of keeping
# the documents from drifting apart (workflow §11.4 — "the skills execute; this page
# carries shape only" — is the rule it protects). Checks are deliberately conservative —
# major §-numbers only (sub-items are often list entries, not headings), exact
# patterns, no guessing — because a false positive here erodes trust in the script.
# Historical task records (docs/tasks/) are excluded: they describe the tree as it
# was, not as it is. Exit 0 = clean; non-zero = violations listed on stdout.
set -uo pipefail
root="${1:-.}"
cd "$root" || exit 2
fail=0
err() { echo "FAIL: $*"; fail=1; }

# Living documents scanned for cross-references.
living_docs() {
  ls AGENTS.md CONSTITUTION.md README.md docs/*.md docs/adr/*.md \
     docs/architecture/*.md docs/adapters/*.md \
     docs/tasks/README.md docs/tasks/TEMPLATE.md \
     .claude/skills/*/SKILL.md 2>/dev/null
}

# --- 1. §-references resolve (major number, context-aware) ----------------------
wf_has()   { grep -qE "^## ${1}\." docs/workflow.md; }
cons_has() { grep -qE "^## ${1}\." CONSTITUTION.md; }
# A line may name several documents (or none). Collect every candidate target the
# line/file context offers and pass if the section exists in ANY of them — either
# route resolving means the reference is not dangling. No candidates = no guess.
while IFS= read -r f <&3; do
  while IFS= read -r line; do
    cands=""
    # ".t-workflow/" (the scripts directory) contains "workflow" as a substring — strip
    # it before matching so a line naming that path alone doesn't falsely candidate
    # docs/workflow.md.
    wfline="${line//.t-workflow/}"
    case "$line" in *CONSTITUTION*) cands="$cands cons";; esac
    case "$wfline" in *workflow*)   cands="$cands wf";;   esac
    # The file's own document is always a legitimate target for its §-references.
    case "$f" in
      docs/workflow.md) cands="$cands wf";;
      CONSTITUTION.md)  cands="$cands cons";;
    esac
    [ -z "$cands" ] && continue
    for n in $(printf '%s\n' "$line" | grep -oE '§[0-9]+' | tr -d '§' | sort -u); do
      ok=1
      for c in $cands; do
        case "$c" in wf) wf_has "$n" && ok=0;; cons) cons_has "$n" && ok=0;; esac
      done
      [ "$ok" -ne 0 ] && err "$f: §$n resolves in none of [$cands] ($line)"
    done
  done < <(grep -E '§[0-9]+' "$f" || true)
done 3< <(living_docs)

# --- 2. ADR pointers resolve, file and decision heading -------------------------
# Inline-code spans (`...`) are illustrative examples, not references — strip them.
for n in $(living_docs | tr '\n' '\0' | xargs -0 cat | sed 's/`[^`]*`//g' | grep -oE 'ADR-[0-9]{3}' | sort -u | sed 's/ADR-//'); do
  [ "$n" = "000" ] && continue   # template
  ls docs/adr/"$n"-*.md >/dev/null 2>&1 || err "ADR-$n referenced but docs/adr/$n-*.md does not exist"
done
# ADR decision references (ADR-001 §D4, "ADR-001 D3.2", "decision D3") must resolve to a
# "### D<n>." heading in that ADR. Sub-items (D3.2) resolve to their parent heading.
while IFS= read -r f <&3; do
  while IFS= read -r line; do
    n=$(printf '%s\n' "$line" | grep -oE 'ADR-[0-9]{3}' | head -1 | sed 's/ADR-//')
    [ -z "$n" ] && continue
    adr=$(ls docs/adr/"$n"-*.md 2>/dev/null | head -1) || true
    [ -z "$adr" ] && continue   # check above already reported the missing file
    for d in $(printf '%s\n' "$line" | grep -oE '\bD[0-9]+' | sort -u); do
      grep -qE "^### ${d}\." "$adr" || err "$f: ADR-$n $d does not resolve to a '### $d.' heading in $adr ($line)"
    done
  done < <(sed 's/`[^`]*`//g' "$f" | grep -E 'ADR-[0-9]{3}[^[:cntrl:]]*\bD[0-9]' || true)
done 3< <(living_docs)

# --- 2b. Named-section references resolve (§Name) -------------------------------
# AGENTS.md is the most-cited document in the skill set and had no validation: a renamed
# heading silently broke every "AGENTS.md §Communication" pointer. Named references are
# matched against headings in the document the same line names.
# A line may name several documents ("AGENTS.md §Checks and CONSTITUTION.md §Amendment"),
# so collect every candidate the line offers and pass if the heading exists in ANY of
# them — the same rule check 1 uses. Guessing a single target produces false positives,
# and a false positive here erodes trust in the whole script.
while IFS= read -r f <&3; do
  while IFS= read -r line; do
    cands=""
    # ".t-workflow/" (the scripts directory) contains "workflow" as a substring — strip
    # it before matching so a line naming that path alone doesn't falsely candidate
    # docs/workflow.md.
    wfline="${line//.t-workflow/}"
    case "$line" in *AGENTS*)       cands="$cands AGENTS.md";;       esac
    case "$line" in *CONSTITUTION*) cands="$cands CONSTITUTION.md";; esac
    case "$wfline" in *workflow*)   cands="$cands docs/workflow.md";; esac
    case "$line" in *README*)       cands="$cands README.md";;       esac
    [ -z "$cands" ] && continue
    # §D4-style ADR decision refs are handled by check 2, not here: a bare "D" would
    # substring-match almost any heading and quietly pass.
    for name in $(printf '%s\n' "$line" | grep -oE '§[A-Z][A-Za-z]+' | tr -d '§' | grep -vE '^D[0-9]*$' | sort -u); do
      ok=1
      for t in $cands; do
        [ -f "$t" ] && grep -qiE "^#{2,3} .*${name}" "$t" && ok=0
      done
      [ "$ok" -ne 0 ] && err "$f: §$name resolves in none of [$cands] ($line)"
    done
  done < <(grep -E '§[A-Z]' "$f" || true)
done 3< <(living_docs)

# --- 3. Skills table symmetry (AGENTS.md ↔ .claude/skills/) ---------------------
for s in $(grep -oE '^\| `/t-[a-z-]+`' AGENTS.md | grep -oE 't-[a-z-]+'); do
  [ -f ".claude/skills/$s/SKILL.md" ] || err "AGENTS.md table names /$s but .claude/skills/$s/SKILL.md is missing"
done
for d in .claude/skills/*/; do
  s=$(basename "$d")
  grep -qE "^\| \`/$s\`" AGENTS.md || err ".claude/skills/$s exists but AGENTS.md's pipeline table has no /$s row"
done
# A consumer's own local-skill rows (docs/architecture/local-slots.md) get the same
# staleness check as the /t-* rows above: a stale row with no matching directory fails
# the same way. Scoped to the "## The pipeline" section specifically — AGENTS.md carries
# a second, unrelated <!-- local --> pair under "## Checks" that this must not read from.
pipeline_section=$(awk '/^## The pipeline/{f=1;next} /^## /{f=0} f' AGENTS.md)
for s in $(printf '%s\n' "$pipeline_section" \
             | awk '/^<!-- local -->$/{f=1;next} /^<!-- \/local -->$/{f=0} f' \
             | grep -oE '^\| `/[a-z][a-z0-9-]*`' | grep -oE '/[a-z0-9-]+' | tr -d '/'); do
  [ -f ".claude/skills/$s/SKILL.md" ] \
    || err "AGENTS.md's local-skill slot (§The pipeline) names /$s but .claude/skills/$s/SKILL.md is missing"
done

# --- 4. Load-bearing phrase present where the no-issue fix path was defined -----
# ADR-001 §D2 is historical (the path it defined is removed, ADR-002); the phrase is
# still required there so the historical record stays legible on its own terms.
for f in docs/adr/001-phase0-delivery-workflow.md; do
  [ -f "$f" ] && { grep -q "no semantic content" "$f" || err "$f defines/constrains the no-issue fix path but lacks the load-bearing phrase 'no semantic content'"; }
done

# --- 5. Lane vocabulary stays retired (ADR-001) ---------------------------------
# Living guidance only: historical ADRs, task records, and GitHub metadata keep the
# vocabulary that was true when they were written.
for f in CONSTITUTION.md AGENTS.md README.md docs/workflow.md \
         .claude/skills/*/SKILL.md; do
  [ -f "$f" ] || continue
  hits=$(grep -niE 'lane: ?(full|light|micro)|(full|light|micro)[ -]lane' "$f" || true)
  [ -n "$hits" ] && err "$f uses lane vocabulary, removed by ADR-001: $(printf '%s' "$hits" | head -1)"
done

# --- 6. Skills are self-contained: no skill delegates to a workflow section -----
# The § must be adjacent to the word "workflow" (either order) — an unbounded gap made
# any skill line that merely mentioned a workflow *and* cited CONSTITUTION.md §3 fail.
for f in .claude/skills/*/SKILL.md; do
  hits=$(grep -niE 'workflow(\.md)?[^[:cntrl:]]{0,3}§|§[0-9.]+[^[:cntrl:]]{0,4}workflow' "$f" || true)
  [ -n "$hits" ] && err "$f cites a workflow section; skills carry their own instructions (ADR-001): $(printf '%s' "$hits" | head -1)"
done

# --- 7. Every tracker:/forge: operation a skill invokes is defined in an adapter -
# The skills name backend-neutral operations; docs/adapters/ maps them to commands. An
# undefined operation is an instruction no backend can execute.
if [ -d docs/adapters ]; then
  defined=$(grep -rhoE '^### `(tracker|forge):[a-z-]+' docs/adapters | sed 's/^### `//' | sort -u)
  for op in $(grep -rhoE '(tracker|forge):[a-z-]+' .claude/skills 2>/dev/null | sort -u); do
    printf '%s\n' "$defined" | grep -qx "$op" \
      || err "skills invoke \`$op\` but no docs/adapters/*.md defines it as an operation"
  done
fi

# --- 9. The protected-path script and CONSTITUTION §3 name the same surfaces ----
# They are one rule in two forms (CONSTITUTION.md §3), so this check runs BOTH ways.
# The reverse direction (9b) is the load-bearing one: deleting patterns from the script
# alone would silently exempt whole surfaces from ever needing a plan or a review — the
# gate-loosening §1.5 and workflow §11.3 forbid — and a forward-only check passes that.
# Guarded on existence, not on the exec bit, and invoked through `bash`: a template
# copied by zip/cp/rsync, or a clone with core.fileMode=false, can arrive without the exec
# bit, and skipping the check silently while still printing OK is exactly the
# absence-indistinguishable-from-a-pass failure this script exists to prevent.
if [ ! -f .t-workflow/scripts/protected-paths.sh ]; then
  err ".t-workflow/scripts/protected-paths.sh is missing; CONSTITUTION.md §3 has no executable twin to check against"
else
  # Only §3's bullet list counts as "named" — bullets plus their wrapped continuation
  # lines. The section's surrounding prose mentions paths too (it points at this very
  # script), and matching that would let a surface be dropped from the list while still
  # appearing to be named.
  sec=$(awk '/^## 3\. Protected surfaces/{f=1;next} /^## 4\./{f=0}
             f && (/^- / || /^  [^ ]/)' CONSTITUTION.md)

  # The backticked path tokens §3 names, one per line — the comparison set for both
  # directions below.
  named=$(printf '%s\n' "$sec" | grep -oE '`[^`]+`' | tr -d '`' | sort -u)

  # 9a. Script -> §3: every enforced pattern is written down for a human to read.
  # Read line by line: the patterns contain globs, and word-splitting an unquoted
  # command substitution would expand them against the working tree instead.
  # Match whole tokens, not substrings: 'README.md' inside 'docs/tasks/README.md' would
  # otherwise look named while the root README was never listed.
  while IFS= read -r pat; do
    base=${pat%\*}                        # docs/adr/* -> docs/adr/ ; README.md -> README.md
    printf '%s\n' "$named" | grep -qxF "$base" \
      || err ".t-workflow/scripts/protected-paths.sh protects '$pat' but CONSTITUTION.md §3 never names '$base'"
  done < <(bash .t-workflow/scripts/protected-paths.sh --list)

  # 9b. §3 -> script: every surface §3 names is actually enforced. Each backticked
  # path-like token in the bullets becomes a probe path passed to the script, so this
  # tests the real decision rather than a spelling match. Tokens containing < or > are
  # placeholders — docs/tasks/<bucket>/ is named there precisely to say individual
  # records are NOT protected — and are skipped.
  while IFS= read -r tok; do
    case "$tok" in *"<"*|*">"*) continue;; esac
    # Path-like = no spaces and only path characters. An earlier version required a slash
    # or a dot, which silently skipped extensionless surfaces like LICENSE — a hole in
    # the very direction this check exists to cover.
    case "$tok" in *[!A-Za-z0-9._/-]*) continue;; esac
    probe="$tok"
    case "$tok" in */) probe="${tok}probe.md";; esac
    bash .t-workflow/scripts/protected-paths.sh "$probe" >/dev/null \
      || err "CONSTITUTION.md §3 names '$tok' as protected but .t-workflow/scripts/protected-paths.sh does not protect it (probed '$probe')"
  done < <(printf '%s\n' "$named")
fi

# --- 10. Code fences open and close cleanly -------------------------------------
# A closing fence with prose on the same line does not close the block: CommonMark wants
# it alone on its line. The fence count stays even, so nothing else notices, and a whole
# section silently renders as code. This bit the branch-deletion rationale in /t-cancel.
while IFS= read -r f <&3; do
  while IFS= read -r hit; do
    err "$f:$hit"
  done < <(awk '
    match($0, /^[ \t]*(```+|~~~+)/) {
      fence = substr($0, RSTART, RLENGTH); sub(/^[ \t]*/, "", fence)
      rest  = substr($0, RSTART + RLENGTH)
      if (!inblock) { inblock = 1; open_line = NR; open_fence = fence; next }
      # Inside a block: a closer is the same fence character; anything but blank after it
      # means the block was never closed where the author thought it was.
      if (substr(fence, 1, 1) == substr(open_fence, 1, 1)) {
        if (rest !~ /^[ \t]*$/)
          printf "%d: closing code fence has text after it (the block stays open): %s\n", NR, $0
        inblock = 0
      }
      next
    }
    END { if (inblock) printf "%d: code fence opened here is never closed\n", open_line }
  ' "$f")
done 3< <(living_docs)

[ "$fail" -eq 0 ] && echo "OK: all consistency checks passed"
exit "$fail"
