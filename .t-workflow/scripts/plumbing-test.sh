#!/usr/bin/env bash
# Tests the mechanical plumbing behind the CI gates added by task #30: that
# protected-paths.sh's exit codes and quoting hold, that the task-id bucket math
# matches ADR-001 §D4, and that every new check-*.sh script fails on a violating
# fixture and passes on a clean one (Done-when 1). Pure fixtures throughout — nothing
# here calls the tracker or the forge, so it runs the same locally and in CI.
#
# Usage: .t-workflow/scripts/plumbing-test.sh   (from the repo root, or anywhere — paths resolve
#                                     from this file)
# Exit 0 = every assertion passed.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
cd "$root" || exit 2

work=$(mktemp -d "${TMPDIR:-/tmp}/t-workflow-plumbing-test.XXXXXX") || exit 2
trap 'rm -rf "$work"' EXIT HUP INT TERM

pass=0
fail=0
ok()  { pass=$((pass + 1)); printf '  ok   %s\n' "$*"; }
bad() { fail=$((fail + 1)); printf '  FAIL %s\n' "$*"; }
# expect_rc <description> <expected-rc> <command...> — runs the command, compares $?.
expect_rc() {
  local desc="$1" want="$2"; shift 2
  local got
  "$@" >/dev/null 2>&1; got=$?
  if [ "$got" -eq "$want" ]; then ok "$desc (rc=$got)"; else bad "$desc (want rc=$want, got rc=$got)"; fi
}
# expect_field <description> <jq-filter> <expected-value> <command...> — runs the
# command, pipes its stdout through the jq filter, compares the (raw) result. Used for
# derive-task-state.sh/parse-task-record.sh, which report a classification or a parsed
# shape in their JSON output rather than only through an exit code.
expect_field() {
  local desc="$1" filter="$2" want="$3"; shift 3
  local out got
  out=$("$@" 2>/dev/null)
  got=$(printf '%s' "$out" | jq -r "$filter" 2>/dev/null)
  if [ "$got" = "$want" ]; then ok "$desc (got $got)"; else bad "$desc (want $want, got $got)"; fi
}

# --- 1. .t-workflow/scripts/protected-paths.sh: exit codes and quoting ------------------
echo "protected-paths.sh"
expect_rc "exit 0 on a protected path"        0 .t-workflow/scripts/protected-paths.sh .t-workflow/scripts/foo.sh
expect_rc "exit 1 when none are protected"    1 .t-workflow/scripts/protected-paths.sh README2.txt src/app.js
expect_rc "exit 2 when nothing was given"     2 .t-workflow/scripts/protected-paths.sh
expect_rc "exit 2 on empty stdin"             2 bash -c 'printf "" | .t-workflow/scripts/protected-paths.sh --stdin'
out=$(printf '"docs/adr/002-caf\303\251.md"\n' | .t-workflow/scripts/protected-paths.sh --stdin)
case "$out" in
  *"docs/adr/002-café.md"*) ok "un-quotes and un-escapes a quoted non-ASCII path" ;;
  *) bad "un-quotes and un-escapes a quoted non-ASCII path (got: $out)" ;;
esac
echo

# --- 2. the bucket math (ADR-001 §D4): id rounded down to the nearest 100, -----
#        zero-padded to 6 digits — the same formula ci.yml's `record` job runs.
echo "bucket math"
bucket_of() { printf '%06d' $(( (10#$1 / 100) * 100 )); }
check_bucket() {
  local id="$1" want="$2" got
  got=$(bucket_of "$id")
  if [ "$got" = "$want" ]; then ok "id $id -> $want"; else bad "id $id -> $want (got $got)"; fi
}
check_bucket 30 000000
check_bucket 99 000000
check_bucket 100 000100
check_bucket 142 000100
check_bucket 7031 007000
echo

# --- 3. .t-workflow/scripts/check-record.sh against fixture diffs -------------------------
echo "check-record.sh"
cat > "$work/good.md" <<'EOF'
# 42 — A Sample Task
Issue: #42

## Asked
x

## Done when
x

## Explicitly not
x

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- none

## Deviations / notes
- none
EOF
expect_rc "a correct record passes" 0 .t-workflow/scripts/check-record.sh 42 "$work/good.md"

printf '# 99 — Wrong id in the heading\nIssue: #42\n' > "$work/bad-heading.md"
expect_rc "a heading with the wrong id fails" 1 .t-workflow/scripts/check-record.sh 42 "$work/bad-heading.md"

printf '# 42 — Right heading\nIssue: #420\n' > "$work/bad-issue-line.md"
expect_rc "an Issue line that only prefix-matches (#420 vs #42) fails" \
  1 .t-workflow/scripts/check-record.sh 42 "$work/bad-issue-line.md"

printf '# 42 — Right heading\nIssue: #42\n\n## Asked\nx\n' > "$work/missing-sections.md"
expect_rc "a record missing template sections fails" \
  1 .t-workflow/scripts/check-record.sh 42 "$work/missing-sections.md"

expect_rc "a missing record file fails" 1 .t-workflow/scripts/check-record.sh 42 "$work/does-not-exist.md"

# ADR-004 --multi mode: a driven initiative's aggregate PR, one record per Task: #<id>.
mkdir -p "$work/docs/tasks/000000"
cp "$work/good.md" "$work/docs/tasks/000000/42-a-sample-task.md"
cat > "$work/docs/tasks/000000/43-another-task.md" <<'EOF'
# 43 — Another Task
Issue: #43

## Asked
y

## Done when
y

## Explicitly not
y

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- none

## Deviations / notes
- none
EOF
printf 'Task: #42 — docs/tasks/000000/42-a-sample-task.md\nTask: #43 — docs/tasks/000000/43-another-task.md\n' \
  > "$work/prbody-both.md"
printf 'Task: #42 — docs/tasks/000000/42-a-sample-task.md\nTask: #99 — docs/tasks/000000/99-missing.md\n' \
  > "$work/prbody-missing.md"
printf 'no Task lines here\n' > "$work/prbody-none.md"
changed_both=$(printf 'docs/tasks/000000/42-a-sample-task.md\ndocs/tasks/000000/43-another-task.md\n')

# The changed-path entries are repo-relative, matching how ci.yml's `record` job
# produces them from a real checkout — so run from a fixture "repo root" ($work) with
# absolute paths for the script, the PR body, and the template.
expect_rc "--multi: every Task: line has a real record, all pass" \
  0 bash -c 'cd "$1" && printf "%s\n" "$2" | "$3" --multi "$4" "$5"' \
  _ "$work" "$changed_both" "$root/.t-workflow/scripts/check-record.sh" "$work/prbody-both.md" "$root/docs/tasks/TEMPLATE.md"
expect_rc "--multi: a Task: line with no matching record fails" \
  1 bash -c 'cd "$1" && printf "%s\n" "$2" | "$3" --multi "$4" "$5"' \
  _ "$work" "$changed_both" "$root/.t-workflow/scripts/check-record.sh" "$work/prbody-missing.md" "$root/docs/tasks/TEMPLATE.md"
expect_rc "--multi: no Task: lines at all fails" \
  1 bash -c 'cd "$1" && printf "%s\n" "$2" | "$3" --multi "$4" "$5"' \
  _ "$work" "$changed_both" "$root/.t-workflow/scripts/check-record.sh" "$work/prbody-none.md" "$root/docs/tasks/TEMPLATE.md"
echo

# --- 4. .t-workflow/scripts/check-plan-gate.sh --------------------------------------------
echo "check-plan-gate.sh"
printf 'no plan section here\n' > "$work/body-noplan.md"
printf '## Plan\nsome text\n' > "$work/body-plan.md"
printf '## Plan\nfirst\n## Plan\nsecond\n' > "$work/body-twoplan.md"

expect_rc "protected diff, no Plan section: fails" \
  1 bash -c 'printf ".t-workflow/scripts/foo.sh\n" | .t-workflow/scripts/check-plan-gate.sh "$1"' _ "$work/body-noplan.md"
expect_rc "protected diff, one Plan section: passes" \
  0 bash -c 'printf ".t-workflow/scripts/foo.sh\n" | .t-workflow/scripts/check-plan-gate.sh "$1"' _ "$work/body-plan.md"
expect_rc "protected diff, two Plan sections: fails" \
  1 bash -c 'printf ".t-workflow/scripts/foo.sh\n" | .t-workflow/scripts/check-plan-gate.sh "$1"' _ "$work/body-twoplan.md"
expect_rc "unprotected diff: passes regardless of the issue body" \
  0 bash -c 'printf "README2.txt\n" | .t-workflow/scripts/check-plan-gate.sh "$1"' _ "$work/body-noplan.md"
echo

# --- 5. .t-workflow/scripts/check-title-gate.sh -------------------------------------------
echo "check-title-gate.sh"
expect_rc "title starting [<id>] passes"  0 .t-workflow/scripts/check-title-gate.sh 30 "[30] The issue title"
expect_rc "title with no prefix fails"    1 .t-workflow/scripts/check-title-gate.sh 30 "The issue title"
expect_rc "title with the wrong id fails" 1 .t-workflow/scripts/check-title-gate.sh 30 "[300] The issue title"
echo

# --- 6. .t-workflow/scripts/check-blocker-gate.sh -----------------------------------------
echo "check-blocker-gate.sh"
printf '[{"number":25,"state":"CLOSED","stateReason":"COMPLETED"}]' > "$work/blockers-ok.json"
printf '[{"number":25,"state":"OPEN","stateReason":null}]'         > "$work/blockers-open.json"
printf '[{"number":25,"state":"CLOSED","stateReason":"NOT_PLANNED"}]' > "$work/blockers-cancelled.json"
printf '[]' > "$work/blockers-none.json"

expect_rc "every blocker closed as completed: passes" 0 .t-workflow/scripts/check-blocker-gate.sh "$work/blockers-ok.json"
expect_rc "no blockers at all: passes"                0 .t-workflow/scripts/check-blocker-gate.sh "$work/blockers-none.json"
expect_rc "an open blocker: fails"                    1 .t-workflow/scripts/check-blocker-gate.sh "$work/blockers-open.json"
expect_rc "a cancelled (not-planned) blocker: fails — abandoned, not satisfied" \
  1 .t-workflow/scripts/check-blocker-gate.sh "$work/blockers-cancelled.json"
printf 'not json' > "$work/blockers-broken.json"
expect_rc "an unparsable blockers file is a usage error, never an OK" \
  2 .t-workflow/scripts/check-blocker-gate.sh "$work/blockers-broken.json"

# ADR-009 (#127): the driven reading. A blocker that is a sibling child of the same
# initiative is satisfied when its PR is MERGED, head wip/<sibling>-*, base
# wip/<initiative>-integration, and its latest review reads `readiness: ready`. The
# sibling dispositions arrive as a file (--siblings <initiative-id> <file>) in the shape
# forge:pr-find-by-task and forge:pr-reviews return. Each printf writes one line of
# JSON with the review body's newlines escaped, so the file parses.
sib() { printf '[{"number":25,"prs":[{"state":"%s","headRefName":"%s","baseRefName":"%s"}],"reviews":[{"submittedAt":"2026-09-01T00:00:00Z","body":"isolation: fresh session\\n\\nreadiness: %s"}]}]' "$@"; }
sib MERGED wip/25-foo wip/40-integration ready      > "$work/sib-merged-ready.json"
sib MERGED wip/25-foo wip/40-integration not-ready  > "$work/sib-merged-notready.json"
sib MERGED wip/25-foo main                ready     > "$work/sib-merged-trunk.json"
sib MERGED wip/250-foo wip/40-integration ready     > "$work/sib-merged-wronghead.json"
sib OPEN   wip/25-foo wip/40-integration ready      > "$work/sib-pr-open.json"
printf '[{"number":25,"prs":[{"state":"MERGED","headRefName":"wip/25-foo","baseRefName":"wip/40-integration"}],"reviews":[{"submittedAt":"2026-09-02T00:00:00Z","body":"readiness: not-ready"},{"submittedAt":"2026-09-01T00:00:00Z","body":"isolation: fresh session\\n\\nreadiness: ready"}]}]' \
  > "$work/sib-later-review-notready.json"
printf '[{"number":25,"state":"OPEN","stateReason":null},{"number":9,"state":"OPEN","stateReason":null}]' \
  > "$work/blockers-open-plus-outside.json"

expect_rc "driven: sibling merged onto the integration branch + latest review ready: passes" \
  0 .t-workflow/scripts/check-blocker-gate.sh "$work/blockers-open.json" --siblings 40 "$work/sib-merged-ready.json"
expect_rc "driven: sibling merged but latest review not-ready: fails" \
  1 .t-workflow/scripts/check-blocker-gate.sh "$work/blockers-open.json" --siblings 40 "$work/sib-merged-notready.json"
expect_rc "driven: sibling merged but a later review is not-ready: fails (latest wins)" \
  1 .t-workflow/scripts/check-blocker-gate.sh "$work/blockers-open.json" --siblings 40 "$work/sib-later-review-notready.json"
expect_rc "driven: sibling merged onto the trunk, not the integration branch: fails" \
  1 .t-workflow/scripts/check-blocker-gate.sh "$work/blockers-open.json" --siblings 40 "$work/sib-merged-trunk.json"
expect_rc "driven: a merged PR whose head is another task's (wip/250-* for #25): fails" \
  1 .t-workflow/scripts/check-blocker-gate.sh "$work/blockers-open.json" --siblings 40 "$work/sib-merged-wronghead.json"
expect_rc "driven: sibling's PR still open: fails" \
  1 .t-workflow/scripts/check-blocker-gate.sh "$work/blockers-open.json" --siblings 40 "$work/sib-pr-open.json"
expect_rc "driven: sibling cancelled (not-planned), whatever its PR says: fails" \
  1 .t-workflow/scripts/check-blocker-gate.sh "$work/blockers-cancelled.json" --siblings 40 "$work/sib-merged-ready.json"
expect_rc "driven: an outside blocker still open, even with a satisfied sibling: fails" \
  1 .t-workflow/scripts/check-blocker-gate.sh "$work/blockers-open-plus-outside.json" --siblings 40 "$work/sib-merged-ready.json"
expect_rc "driven: the siblings file is judged under a different initiative id: fails" \
  1 .t-workflow/scripts/check-blocker-gate.sh "$work/blockers-open.json" --siblings 41 "$work/sib-merged-ready.json"
expect_rc "driven: an unparsable siblings file is a usage error" \
  2 .t-workflow/scripts/check-blocker-gate.sh "$work/blockers-open.json" --siblings 40 "$work/blockers-broken.json"
expect_rc "driven: a non-numeric initiative id is a usage error" \
  2 .t-workflow/scripts/check-blocker-gate.sh "$work/blockers-open.json" --siblings abc "$work/sib-merged-ready.json"

# --pr-base: CI passes the PR's base ref; an integration-branch PR is not re-judged
# (the driving session already judged it, ADR-009 D2); any other base judges normally.
expect_rc "--pr-base wip/40-integration with an open blocker: passes (not re-judged)" \
  0 .t-workflow/scripts/check-blocker-gate.sh "$work/blockers-open.json" --pr-base wip/40-integration
expect_rc "--pr-base main with an open blocker: fails (judged normally)" \
  1 .t-workflow/scripts/check-blocker-gate.sh "$work/blockers-open.json" --pr-base main
expect_rc "--pr-base wip/40-foo (a task branch, not an integration branch) with an open blocker: fails" \
  1 .t-workflow/scripts/check-blocker-gate.sh "$work/blockers-open.json" --pr-base wip/40-foo
expect_rc "--pr-base with no ref is a usage error" \
  2 .t-workflow/scripts/check-blocker-gate.sh "$work/blockers-open.json" --pr-base
echo

# --- 7. .t-workflow/scripts/check-review-gate.sh ------------------------------------------
echo "check-review-gate.sh"
head_time="2026-08-28T22:44:11Z"
cat > "$work/rev-ready.json"       <<'EOF'
[{"submittedAt":"2026-08-28T22:47:32Z","body":"isolation: fresh session\n\nreadiness: ready"}]
EOF
cat > "$work/rev-notready.json"    <<'EOF'
[{"submittedAt":"2026-08-28T22:47:32Z","body":"isolation: fresh session\n\nreadiness: not-ready"}]
EOF
cat > "$work/rev-samesession.json" <<'EOF'
[{"submittedAt":"2026-08-28T22:47:32Z","body":"isolation: same session (tiny)\n\nreadiness: ready"}]
EOF
cat > "$work/rev-noisolation.json" <<'EOF'
[{"submittedAt":"2026-08-28T22:47:32Z","body":"readiness: ready"}]
EOF
cat > "$work/rev-stale.json"       <<'EOF'
[{"submittedAt":"2026-08-28T20:00:00Z","body":"isolation: fresh session\n\nreadiness: ready"}]
EOF
printf '[]' > "$work/rev-none.json"

expect_rc "protected + current ready fresh review: passes" \
  0 bash -c 'printf ".t-workflow/scripts/foo.sh\n" | .t-workflow/scripts/check-review-gate.sh "$1" "$2"' _ "$head_time" "$work/rev-ready.json"
expect_rc "protected + readiness not-ready: fails" \
  1 bash -c 'printf ".t-workflow/scripts/foo.sh\n" | .t-workflow/scripts/check-review-gate.sh "$1" "$2"' _ "$head_time" "$work/rev-notready.json"
expect_rc "protected + isolation same session: fails" \
  1 bash -c 'printf ".t-workflow/scripts/foo.sh\n" | .t-workflow/scripts/check-review-gate.sh "$1" "$2"' _ "$head_time" "$work/rev-samesession.json"
expect_rc "protected + no isolation line: fails (unknown, not cold)" \
  1 bash -c 'printf ".t-workflow/scripts/foo.sh\n" | .t-workflow/scripts/check-review-gate.sh "$1" "$2"' _ "$head_time" "$work/rev-noisolation.json"
expect_rc "protected + review predates head commit: fails" \
  1 bash -c 'printf ".t-workflow/scripts/foo.sh\n" | .t-workflow/scripts/check-review-gate.sh "$1" "$2"' _ "$head_time" "$work/rev-stale.json"
expect_rc "protected + no review at all: fails" \
  1 bash -c 'printf ".t-workflow/scripts/foo.sh\n" | .t-workflow/scripts/check-review-gate.sh "$1" "$2"' _ "$head_time" "$work/rev-none.json"
expect_rc "unprotected diff: passes without needing a review" \
  0 bash -c 'printf "README2.txt\n" | .t-workflow/scripts/check-review-gate.sh "$1" "$2"' _ "$head_time" "$work/rev-none.json"
echo

# --- 8. .t-workflow/scripts/check-manifest.sh (issue #20) ---------------------------------
echo "check-manifest.sh"

# Normalization: a real <!-- local --> ... <!-- /local --> region never affects the
# hash; a line that only *mentions* a marker in prose (this repo's own manifest.md and
# local-slots.md do) must not be mistaken for one and must still affect the hash — the
# bug the manual rehearsal in docs/tasks/000000/20-*.md caught before this fixture
# existed.
printf 'a\n<!-- local -->\nsecret\n<!-- /local -->\nb\n' > "$work/slot-a.md"
printf 'a\n<!-- local -->\nDIFFERENT\n<!-- /local -->\nb\n' > "$work/slot-a-editedinside.md"
printf 'a\n<!-- local -->\nsecret\n<!-- /local -->\nb-changed\n' > "$work/slot-a-editedoutside.md"
printf 'x\nThis doc mentions `<!-- local -->` and `<!-- /local -->` in prose.\ny\n' > "$work/prose-a.md"
printf 'x\nThis doc mentions `<!-- local -->` and `<!-- /local -->` in prose.\nCHANGED\n' > "$work/prose-a-edited.md"

h_slot=$(.t-workflow/scripts/check-manifest.sh --hash-file "$work/slot-a.md")
h_slot_inside=$(.t-workflow/scripts/check-manifest.sh --hash-file "$work/slot-a-editedinside.md")
h_slot_outside=$(.t-workflow/scripts/check-manifest.sh --hash-file "$work/slot-a-editedoutside.md")
h_prose=$(.t-workflow/scripts/check-manifest.sh --hash-file "$work/prose-a.md")
h_prose_edited=$(.t-workflow/scripts/check-manifest.sh --hash-file "$work/prose-a-edited.md")

if [ "$h_slot" = "$h_slot_inside" ]; then ok "an edit inside a real slot does not change the hash"
else bad "an edit inside a real slot does not change the hash"; fi
if [ "$h_slot" != "$h_slot_outside" ]; then ok "an edit outside a real slot changes the hash"
else bad "an edit outside a real slot changes the hash"; fi
if [ "$h_prose" != "$h_prose_edited" ]; then ok "an edit after a prose mention of the markers still changes the hash"
else bad "an edit after a prose mention of the markers still changes the hash"; fi

# A comment-syntax file (ci.yml's YAML) writes its markers as indented line-comments,
# never a bare marker line — a bare one is not valid YAML (issue #118). The regex must
# strip a comment-prefixed, indented region the same way it strips a bare one.
printf 'a: 1\n    # <!-- local -->\n    secret: 1\n    # <!-- /local -->\nb: 2\n' > "$work/slot-yaml.yml"
printf 'a: 1\n    # <!-- local -->\n    secret: 2\n    # <!-- /local -->\nb: 2\n' > "$work/slot-yaml-editedinside.yml"
printf 'a: 1\n    # <!-- local -->\n    secret: 1\n    # <!-- /local -->\nb: 3\n' > "$work/slot-yaml-editedoutside.yml"

h_yaml=$(.t-workflow/scripts/check-manifest.sh --hash-file "$work/slot-yaml.yml")
h_yaml_inside=$(.t-workflow/scripts/check-manifest.sh --hash-file "$work/slot-yaml-editedinside.yml")
h_yaml_outside=$(.t-workflow/scripts/check-manifest.sh --hash-file "$work/slot-yaml-editedoutside.yml")

if [ "$h_yaml" = "$h_yaml_inside" ]; then ok "an edit inside a comment-prefixed YAML slot does not change the hash"
else bad "an edit inside a comment-prefixed YAML slot does not change the hash"; fi
if [ "$h_yaml" != "$h_yaml_outside" ]; then ok "an edit outside a comment-prefixed YAML slot changes the hash"
else bad "an edit outside a comment-prefixed YAML slot changes the hash"; fi

# .gitignore's own local slot (issue #130): a consumer's real entries inside the
# markers must never register as drift against the template's own (empty-slot) file.
awk '{ print } /^# <!-- local -->$/ { print "target/"; print "data/"; print "*.db" }' \
  .gitignore > "$work/gitignore-filled"
h_gitignore_template=$(.t-workflow/scripts/check-manifest.sh --hash-file .gitignore)
h_gitignore_filled=$(.t-workflow/scripts/check-manifest.sh --hash-file "$work/gitignore-filled")
if [ "$h_gitignore_template" = "$h_gitignore_filled" ]; then
  ok "a consumer's real .gitignore entries inside the local slot do not change the hash"
else
  bad "a consumer's real .gitignore entries inside the local slot do not change the hash"
fi

expect_rc "--hash-file on a symlink-to-directory does not error" \
  0 bash -c 'ln -sfn ../x "$1/link-to-dir" && .t-workflow/scripts/check-manifest.sh --hash-file "$1/link-to-dir"' _ "$work"
h_link1=$(.t-workflow/scripts/check-manifest.sh --hash-file "$work/link-to-dir")
ln -sfn ../y "$work/link-to-dir"
h_link2=$(.t-workflow/scripts/check-manifest.sh --hash-file "$work/link-to-dir")
if [ "$h_link1" != "$h_link2" ]; then ok "a symlink's hash changes when its target changes"
else bad "a symlink's hash changes when its target changes"; fi

expect_rc "--hash-file on a missing file fails" 2 .t-workflow/scripts/check-manifest.sh --hash-file "$work/does-not-exist.md"
expect_rc "no arguments and no manifest at CWD fails (nothing checked)" \
  2 bash -c 'cd "$1" && "$2/.t-workflow/scripts/check-manifest.sh"' _ "$work" "$root"

# Verify mode against a small fixture manifest.
mkdir -p "$work/mrepo"
printf 'a\n<!-- local -->\nsecret\n<!-- /local -->\nb\n' > "$work/mrepo/f.md"
h_f=$("$root/.t-workflow/scripts/check-manifest.sh" --hash-file "$work/mrepo/f.md")
printf '{"template":"x/y","tag":"v1","migrations_applied":0,"files":{"f.md":"%s"}}' "$h_f" \
  > "$work/mrepo/.template-manifest.json"
expect_rc "verify mode: clean tree matches its manifest" \
  0 bash -c 'cd "$1" && "$2/.t-workflow/scripts/check-manifest.sh"' _ "$work/mrepo" "$root"
printf 'a\n<!-- local -->\nsecret\n<!-- /local -->\nb-drifted\n' > "$work/mrepo/f.md"
expect_rc "verify mode: an edit outside the slot is reported as drift" \
  1 bash -c 'cd "$1" && "$2/.t-workflow/scripts/check-manifest.sh"' _ "$work/mrepo" "$root"
rm "$work/mrepo/f.md"
expect_rc "verify mode: a file the manifest lists but the tree lacks fails" \
  1 bash -c 'cd "$1" && "$2/.t-workflow/scripts/check-manifest.sh"' _ "$work/mrepo" "$root"
echo

# --- 9. .t-workflow/scripts/status-snapshot.sh: usage only ---------------------------------
# The script's live gh/git calls need a real repo and network access, so this fixture
# suite only asserts its argument handling — no `gh` call is reachable from here.
echo "status-snapshot.sh"
expect_rc "an unexpected argument is a usage error, no gh call reached" \
  2 .t-workflow/scripts/status-snapshot.sh unexpected-argument
echo

# --- 10. .t-workflow/scripts/review-snapshot.sh: usage only ---------------------------------
# Same limitation as status-snapshot.sh above: only argument handling is fixture-able.
echo "review-snapshot.sh"
expect_rc "no arguments is a usage error, no gh call reached" \
  2 .t-workflow/scripts/review-snapshot.sh
expect_rc "one argument is a usage error, no gh call reached" \
  2 .t-workflow/scripts/review-snapshot.sh 73
expect_rc "three arguments is a usage error, no gh call reached" \
  2 .t-workflow/scripts/review-snapshot.sh 73 5 extra
expect_rc "a non-numeric task-id is a usage error, no gh call reached" \
  2 .t-workflow/scripts/review-snapshot.sh abc 5
expect_rc "a non-numeric pr is a usage error, no gh call reached" \
  2 .t-workflow/scripts/review-snapshot.sh 73 abc
echo

# --- 11. consistency-check.sh: local-skill row symmetry (issue #120) ------------
# check 3's first loop now walks AGENTS.md's local-skill slot (docs/architecture/
# local-slots.md) the same way it walks the /t-* rows: a stale row with no matching
# SKILL.md fails. A full copy of the working tree gives every *other* check in
# consistency-check.sh something real to pass against, so only the mutation under
# test can make it fail — a bare-bones fixture repo would fail on unrelated grounds
# (missing CONSTITUTION.md sections, protected-paths.sh absent, etc).
echo "consistency-check.sh (local-skill row symmetry)"

make_fixture_repo() {
  # $1 = destination dir
  mkdir -p "$1"
  git -C "$root" ls-files -z | tar -C "$root" --null -T - -cf - | tar -xf - -C "$1"
}

insert_local_skill_row() {
  # $1 = AGENTS.md path, $2 = row text to insert into the pipeline-section slot
  awk -v row="$2" '
    /^## The pipeline/ { inpipe=1 }
    /^## / && $0 !~ /^## The pipeline/ { inpipe=0 }
    { print }
    inpipe && /^<!-- local -->$/ && !inserted { print row; inserted=1 }
  ' "$1" > "$1.new" && mv "$1.new" "$1"
}

fixture_ok="$work/fixture-ok"
make_fixture_repo "$fixture_ok"
mkdir -p "$fixture_ok/.claude/skills/l-example"
printf '# /l-example\nAn example consumer skill.\n' > "$fixture_ok/.claude/skills/l-example/SKILL.md"
insert_local_skill_row "$fixture_ok/AGENTS.md" '| `/l-example` | An example consumer skill. |'
expect_rc "a local-skill row with a matching SKILL.md: passes" \
  0 .t-workflow/scripts/consistency-check.sh "$fixture_ok"

fixture_stale="$work/fixture-stale"
make_fixture_repo "$fixture_stale"
insert_local_skill_row "$fixture_stale/AGENTS.md" '| `/l-missing` | A skill with no directory. |'
out=$(.t-workflow/scripts/consistency-check.sh "$fixture_stale" 2>&1)
rc=$?
if [ "$rc" -ne 0 ]; then ok "a local-skill row with no matching SKILL.md: fails (rc=$rc)"
else bad "a local-skill row with no matching SKILL.md: fails (rc=$rc)"; fi
case "$out" in
  *"local-skill slot"*"/l-missing"*) ok "the failure names the missing skill by row" ;;
  *) bad "the failure names the missing skill by row (got: $out)" ;;
esac
echo

# --- 12. .t-workflow/scripts/template-owned-paths.sh (issue #122) -------------------------
# A minimal real git repo — unlike consistency-check.sh's plain-directory fixtures
# above, template-owned-paths.sh calls bare `git ls-files`, so the fixture needs an
# actual repo. One file the template would ship (AGENTS.md, a manifest key) and one a
# consumer added themselves under a protected directory the template never put it in
# (.claude/skills/l-example/SKILL.md, not a manifest key) — issue #122's own repro
# shape.
echo "template-owned-paths.sh"

towned_repo="$work/towned-repo"
mkdir -p "$towned_repo/.claude/skills/l-example"
git -C "$towned_repo" init -q -b main
printf '# AGENTS.md\n' > "$towned_repo/AGENTS.md"
printf '# /l-example\nA consumer-authored skill, not shipped by the template.\n' \
  > "$towned_repo/.claude/skills/l-example/SKILL.md"
printf 'not protected\n' > "$towned_repo/README2.txt"
git -C "$towned_repo" add -A
git -C "$towned_repo" -c user.email=test@example.com -c user.name=test commit -q -m "fixture"

out_nomanifest=$(bash -c 'cd "$1" && "$2/.t-workflow/scripts/template-owned-paths.sh" --list' _ "$towned_repo" "$root")
case "$out_nomanifest" in
  *".claude/skills/l-example/SKILL.md"*) ok "no manifest present: pattern match alone still reports a protected-directory file (baseline)" ;;
  *) bad "no manifest present: pattern match alone still reports a protected-directory file (got: $out_nomanifest)" ;;
esac

h_agents=$("$root/.t-workflow/scripts/check-manifest.sh" --hash-file "$towned_repo/AGENTS.md")
printf '{"template":"x/y","tag":"v1","migrations_applied":0,"files":{"AGENTS.md":"%s"}}' "$h_agents" \
  > "$towned_repo/.template-manifest.json"

out_withmanifest=$(bash -c 'cd "$1" && "$2/.t-workflow/scripts/template-owned-paths.sh" --list' _ "$towned_repo" "$root")
case "$out_withmanifest" in
  *".claude/skills/l-example/SKILL.md"*)
    bad "manifest present: a consumer-added file under a protected directory, not a manifest key, is no longer reported (got: $out_withmanifest)" ;;
  *)
    ok "manifest present: a consumer-added file under a protected directory, not a manifest key, is no longer reported" ;;
esac
case "$out_withmanifest" in
  *"AGENTS.md"*) ok "manifest present: a file that is a manifest key is still reported" ;;
  *) bad "manifest present: a file that is a manifest key is still reported (got: $out_withmanifest)" ;;
esac
echo

# --- 13. .t-workflow/scripts/trunk-ref.sh (issue #124) --------------------------
# Resolves the checkout's actual trunk instead of hardcoding `main`. A bare "remote"
# whose default branch is not `main`, cloned normally, sets `origin/HEAD` the same way
# a real consumer clone would — the resolver must follow it rather than falling back.
# A fresh repo with no remote at all has no `origin/HEAD` to read — the resolver's one
# permitted fallback.
echo "trunk-ref.sh"

trunk_bare="$work/trunk-bare"
git init -q --bare -b trunk-test "$trunk_bare"
trunk_src="$work/trunk-src"
git init -q -b trunk-test "$trunk_src"
git -C "$trunk_src" -c user.email=test@example.com -c user.name=test \
  commit -q --allow-empty -m "init"
git -C "$trunk_src" remote add origin "$trunk_bare"
git -C "$trunk_src" push -q origin trunk-test
trunk_clone="$work/trunk-clone"
git clone -q "$trunk_bare" "$trunk_clone"

out_nonmain=$(cd "$trunk_clone" && "$root/.t-workflow/scripts/trunk-ref.sh")
case "$out_nonmain" in
  "trunk-test") ok "a checkout whose default branch is not main resolves to it (got: $out_nonmain)" ;;
  *) bad "a checkout whose default branch is not main resolves to it (got: $out_nonmain)" ;;
esac

no_remote_repo="$work/no-remote-repo"
git init -q "$no_remote_repo"
out_noremote=$(cd "$no_remote_repo" && "$root/.t-workflow/scripts/trunk-ref.sh")
case "$out_noremote" in
  "main") ok "no origin/HEAD to read falls back to main (got: $out_noremote)" ;;
  *) bad "no origin/HEAD to read falls back to main (got: $out_noremote)" ;;
esac
echo

# --- 14. .t-workflow/scripts/required-checks.sh (issue #126) --------------------
# The one implementation of the required-status-check list github-bootstrap.sh
# asserts and /t-ship flips to. Pure fixtures: the consumer file and the observed
# trunk check-run names are both inputs, so no forge call is needed.
echo "required-checks.sh"
rc="$root/.t-workflow/scripts/required-checks.sh"
rc_none="$work/required-checks-none.local"   # deliberately never created
rc_file="$work/required-checks.local"
printf '# consumer additions\nmac-lifecycle  # real macOS run\n\n  lint\nchecks\nlint\n' > "$rc_file"

out=$("$rc" --list --local-file "$rc_none")
if [ "$out" = $'checks\ncold-review' ]; then ok "--list with no consumer file is exactly checks, cold-review"
else bad "--list with no consumer file is exactly checks, cold-review (got: $(printf '%s' "$out" | paste -sd, -))"; fi

out=$("$rc" --list --local-file "$rc_file")
if [ "$out" = $'checks\ncold-review\nmac-lifecycle\nlint' ]; then
  ok "--list with a consumer file is the union, file order, comments/blanks/duplicates dropped"
else bad "--list with a consumer file is the union (got: $(printf '%s' "$out" | paste -sd, -))"; fi

out=$(printf 'checks\ncold-review\nmac-lifecycle\n' | "$rc" --asserted - --local-file "$rc_file" 2>/dev/null)
if [ "$out" = $'checks\ncold-review\nmac-lifecycle' ]; then
  ok "--asserted keeps a consumer context that has a trunk run and drops one that has none"
else bad "--asserted keeps a consumer context that has a trunk run and drops one that has none (got: $(printf '%s' "$out" | paste -sd, -))"; fi

err=$(printf 'checks\n' | "$rc" --asserted - --local-file "$rc_file" 2>&1 >/dev/null)
case "$err" in
  *"'lint'"*"no run on the trunk yet"*) ok "--asserted names each consumer context it leaves out" ;;
  *) bad "--asserted names each consumer context it leaves out (stderr: $err)" ;;
esac

out=$(printf '' | "$rc" --asserted - --local-file "$rc_none")
if [ "$out" = $'checks\ncold-review' ]; then
  ok "--asserted with no consumer file asserts the template pair regardless of observed names"
else bad "--asserted with no consumer file asserts the template pair (got: $(printf '%s' "$out" | paste -sd, -))"; fi

printf 'checks\nmac-lifecycle\n' > "$work/observed.txt"
out=$("$rc" --asserted "$work/observed.txt" --local-file "$rc_file" 2>/dev/null)
if [ "$out" = $'checks\ncold-review\nmac-lifecycle' ]; then ok "--asserted reads observed names from a file as well as stdin"
else bad "--asserted reads observed names from a file as well as stdin (got: $(printf '%s' "$out" | paste -sd, -))"; fi

expect_rc "no mode is a usage error"                       2 "$rc"
expect_rc "--asserted with an unreadable observed file fails" 2 "$rc" --asserted "$work/does-not-exist.txt"
expect_rc "the consumer file is not a protected path"      1 .t-workflow/scripts/protected-paths.sh .t-workflow/required-checks.local
echo

# --- 15. consistency-check.sh: protected-path local-slot symmetry (issue #134) ------------
# CONSTITUTION.md §3 and protected-paths.sh's patterns array now each carry a
# `<!-- local -->` slot for a consumer's own protected-path bullets/patterns
# (docs/architecture/local-slots.md). Check 9's existing "both ways" symmetry between
# §3 and protected-paths.sh is generic — it scans the whole §3 bullet list and the
# whole `--list` output, markers included — so it should need no code change to also
# catch a consumer addition inside the new slot. This proves that, the same way #11
# above proves the local-skill-row slot's own symmetry check.
echo "consistency-check.sh (protected-path local-slot symmetry)"

insert_protected_bullet() {
  # $1 = CONSTITUTION.md path, $2 = bullet line to insert into §3's own local slot
  awk -v row="$2" '
    /^## 3\. Protected surfaces/ { insec=1 }
    /^## 4\./ { insec=0 }
    { print }
    insec && /^<!-- local -->$/ && !inserted { print row; inserted=1 }
  ' "$1" > "$1.new" && mv "$1.new" "$1"
}

insert_protected_pattern() {
  # $1 = protected-paths.sh path, $2 = pattern line to insert into its own local slot
  awk -v row="$2" '
    { print }
    /^  # <!-- local -->$/ && !inserted { print row; inserted=1 }
  ' "$1" > "$1.new" && mv "$1.new" "$1"
}

bullet="- \`db/migrations/\` (a consumer's own migration files)"
pattern="  'db/migrations/*'"

fixture_sym_ok="$work/fixture-sym-ok"
make_fixture_repo "$fixture_sym_ok"
insert_protected_bullet "$fixture_sym_ok/CONSTITUTION.md" "$bullet"
insert_protected_pattern "$fixture_sym_ok/.t-workflow/scripts/protected-paths.sh" "$pattern"
expect_rc "a slot bullet with a matching slot pattern: passes" \
  0 .t-workflow/scripts/consistency-check.sh "$fixture_sym_ok"

fixture_sym_bulletonly="$work/fixture-sym-bulletonly"
make_fixture_repo "$fixture_sym_bulletonly"
insert_protected_bullet "$fixture_sym_bulletonly/CONSTITUTION.md" "$bullet"
out=$(.t-workflow/scripts/consistency-check.sh "$fixture_sym_bulletonly" 2>&1)
rc=$?
if [ "$rc" -ne 0 ]; then ok "a slot bullet with no matching pattern: fails (rc=$rc)"
else bad "a slot bullet with no matching pattern: fails (rc=$rc)"; fi
case "$out" in
  *"protected-paths.sh does not protect it"*"db/migrations/"*) ok "the failure names the unenforced bullet" ;;
  *) bad "the failure names the unenforced bullet (got: $out)" ;;
esac

fixture_sym_patternonly="$work/fixture-sym-patternonly"
make_fixture_repo "$fixture_sym_patternonly"
insert_protected_pattern "$fixture_sym_patternonly/.t-workflow/scripts/protected-paths.sh" "$pattern"
out=$(.t-workflow/scripts/consistency-check.sh "$fixture_sym_patternonly" 2>&1)
rc=$?
if [ "$rc" -ne 0 ]; then ok "a slot pattern with no matching bullet: fails (rc=$rc)"
else bad "a slot pattern with no matching bullet: fails (rc=$rc)"; fi
case "$out" in
  *"CONSTITUTION.md §3 never names"*"db/migrations/"*) ok "the failure names the unlisted pattern" ;;
  *) bad "the failure names the unlisted pattern (got: $out)" ;;
esac
echo

# --- 16. check-manifest.sh: the real §3/protected-paths.sh slots round-trip --------------
# The generic marker-stripping in #8 above is format-agnostic by construction; this
# confirms it holds for these two real, live files specifically — a consumer's filled
# slot must hash identically to the template's own empty one, or every sync reports
# drift that was never really there.
echo "check-manifest.sh (real CONSTITUTION.md / protected-paths.sh slots)"

filled_constitution="$work/CONSTITUTION-filled.md"
cp CONSTITUTION.md "$filled_constitution"
insert_protected_bullet "$filled_constitution" "$bullet"
h_empty=$(.t-workflow/scripts/check-manifest.sh --hash-file CONSTITUTION.md)
h_filled=$(.t-workflow/scripts/check-manifest.sh --hash-file "$filled_constitution")
if [ "$h_empty" = "$h_filled" ]; then ok "CONSTITUTION.md: a filled §3 slot hashes the same as the empty one"
else bad "CONSTITUTION.md: a filled §3 slot hashes the same as the empty one (got $h_filled != $h_empty)"; fi

filled_pp="$work/protected-paths-filled.sh"
cp .t-workflow/scripts/protected-paths.sh "$filled_pp"
insert_protected_pattern "$filled_pp" "$pattern"
h_empty=$(.t-workflow/scripts/check-manifest.sh --hash-file .t-workflow/scripts/protected-paths.sh)
h_filled=$(.t-workflow/scripts/check-manifest.sh --hash-file "$filled_pp")
if [ "$h_empty" = "$h_filled" ]; then ok "protected-paths.sh: a filled patterns slot hashes the same as the empty one"
else bad "protected-paths.sh: a filled patterns slot hashes the same as the empty one (got $h_filled != $h_empty)"; fi
echo

# --- 17. .t-workflow/scripts/check-verification-gate.sh (issue #144) --------------------
# The mechanical form of /t-ship's new verification precondition
# (docs/architecture/verification.md). Pure fixtures throughout — the script never
# touches git or the forge itself, only the small JSON shape /t-ship assembles.
echo "check-verification-gate.sh"
cvg="$root/.t-workflow/scripts/check-verification-gate.sh"

printf '[]' > "$work/verif-none.json"
printf '[{"role":"contributor","required":true,"state":"pending","stale":false}]' \
  > "$work/verif-pending.json"
printf '[{"role":"contributor","required":true,"state":"rejected","stale":false}]' \
  > "$work/verif-rejected.json"
printf '[{"role":"contributor","required":true,"state":"verified","stale":false}]' \
  > "$work/verif-verified-current.json"
printf '[{"role":"contributor","required":true,"state":"verified","stale":true}]' \
  > "$work/verif-verified-stale.json"
printf '[{"role":"maintainer","required":true,"state":"risk-accepted","stale":false}]' \
  > "$work/verif-risk-accepted-current.json"
printf '[{"role":"maintainer","required":true,"state":"risk-accepted","stale":true}]' \
  > "$work/verif-risk-accepted-stale.json"
printf '[{"role":"contributor","required":false,"state":"pending","stale":false}]' \
  > "$work/verif-not-required-pending.json"
printf '[{"role":"a","required":true,"state":"pending","stale":false},{"role":"b","required":true,"state":"verified","stale":false}]' \
  > "$work/verif-mixed-one-bad.json"
printf 'not json' > "$work/verif-malformed.json"
printf '[{"role":"contributor","required":true,"state":"unknown-state","stale":false}]' \
  > "$work/verif-bad-state.json"
printf '[{"role":"contributor","state":"pending","stale":false}]' \
  > "$work/verif-missing-required.json"

expect_rc "no entries at all: passes (a plan/record with none needs no migration)" \
  0 "$cvg" "$work/verif-none.json"
expect_rc "a required entry still pending: fails" \
  1 "$cvg" "$work/verif-pending.json"
expect_rc "a required entry rejected: fails" \
  1 "$cvg" "$work/verif-rejected.json"
expect_rc "a required entry verified and current: passes" \
  0 "$cvg" "$work/verif-verified-current.json"
expect_rc "a required entry verified but stale: fails" \
  1 "$cvg" "$work/verif-verified-stale.json"
expect_rc "a required entry risk-accepted and current: passes" \
  0 "$cvg" "$work/verif-risk-accepted-current.json"
expect_rc "a required entry risk-accepted but stale: fails" \
  1 "$cvg" "$work/verif-risk-accepted-stale.json"
expect_rc "a not-required pending entry never blocks: passes" \
  0 "$cvg" "$work/verif-not-required-pending.json"
expect_rc "one resolved entry alongside one still-pending required entry: fails" \
  1 "$cvg" "$work/verif-mixed-one-bad.json"
expect_rc "malformed JSON is a usage error, not a clean pass" \
  2 "$cvg" "$work/verif-malformed.json"
expect_rc "an unrecognized state is a usage error" \
  2 "$cvg" "$work/verif-bad-state.json"
expect_rc "a missing required field is a usage error" \
  2 "$cvg" "$work/verif-missing-required.json"
expect_rc "a missing input file is a usage error" \
  2 "$cvg" "$work/does-not-exist.json"
expect_rc "no arguments is a usage error" \
  2 "$cvg"

# risk-accepted must never be printed or read as "verified" (ADR-010 §D4;
# CONSTITUTION.md §1.5) — the OK message for a risk-accepted pass names it, never
# borrows the word "verified" for it.
out=$("$cvg" "$work/verif-risk-accepted-current.json")
case "$out" in
  *"verified or risk-accepted"*) ok "the passing message names risk-accepted distinctly, never as \"verified\" alone" ;;
  *) bad "the passing message names risk-accepted distinctly (got: $out)" ;;
esac
out=$("$cvg" "$work/verif-verified-stale.json" 2>&1)
case "$out" in
  *"role contributor"*"verified is stale"*) ok "a stale finding names the entry's role and state" ;;
  *) bad "a stale finding names the entry's role and state (got: $out)" ;;
esac
echo

# --- 18. .t-workflow/scripts/check-preview-evidence.sh (issue #146) ---------------------
# Shape validation only, for docs/adapters/PREVIEW.md's preview-evidence contract. Pure
# fixtures throughout — the script never touches git, the forge, or any deployment
# target; it never gates anything (this repo calls it from nowhere else).
echo "check-preview-evidence.sh"
cpe="$root/.t-workflow/scripts/check-preview-evidence.sh"

printf '[]' > "$work/preview-empty.json"
printf '{"commit":"a1b2c3d4e5f60718293a4b5c6d7e8f9012345678","type":"web","status":"ready","review_url":"https://preview.example.com/x"}' \
  > "$work/preview-web-ready.json"
printf '{"commit":"a1b2c3d4e5f60718293a4b5c6d7e8f9012345678","type":"package","status":"failed","instructions":"install the tarball","failure_reason":"build broke"}' \
  > "$work/preview-package-failed.json"
printf '[{"commit":"abc123","type":"image","status":"pending"},{"commit":"def456","type":"environment","status":"expired","expiry":"2026-01-01T00:00:00Z"}]' \
  > "$work/preview-array-ok.json"
printf '{"type":"web","status":"ready"}' > "$work/preview-missing-commit.json"
printf '{"commit":"","type":"web","status":"ready"}' > "$work/preview-empty-commit.json"
printf '{"commit":"abc","status":"done"}' > "$work/preview-bad-status.json"
printf '{"commit":"abc","status":"failed"}' > "$work/preview-failed-no-reason.json"
printf '{"commit":"abc","status":"ready","failure_reason":"oops"}' > "$work/preview-reason-not-failed.json"
printf '[{"commit":"abc","status":"ready"},{"commit":"","status":"pending"}]' \
  > "$work/preview-array-one-bad.json"
printf '{"commit":"abc","status":"ready","review_url":123}' > "$work/preview-nonstring-field.json"
printf 'not json' > "$work/preview-malformed.json"
printf '"just a string"' > "$work/preview-wrong-type.json"

expect_rc "no entries at all: passes (nothing to check)" \
  0 "$cpe" "$work/preview-empty.json"
expect_rc "a valid single web-preview object: passes" \
  0 "$cpe" "$work/preview-web-ready.json"
expect_rc "a valid non-web (package) preview, failed with a reason: passes" \
  0 "$cpe" "$work/preview-package-failed.json"
expect_rc "an array of valid entries (image, environment): passes" \
  0 "$cpe" "$work/preview-array-ok.json"
expect_rc "missing commit: fails" \
  1 "$cpe" "$work/preview-missing-commit.json"
expect_rc "empty-string commit: fails" \
  1 "$cpe" "$work/preview-empty-commit.json"
expect_rc "unrecognized status: fails" \
  1 "$cpe" "$work/preview-bad-status.json"
expect_rc "status failed with no failure_reason: fails" \
  1 "$cpe" "$work/preview-failed-no-reason.json"
expect_rc "failure_reason present but status is not failed: fails" \
  1 "$cpe" "$work/preview-reason-not-failed.json"
expect_rc "one bad entry alongside one good entry in an array: fails" \
  1 "$cpe" "$work/preview-array-one-bad.json"
expect_rc "an optional field present but not a string: fails" \
  1 "$cpe" "$work/preview-nonstring-field.json"
expect_rc "malformed JSON is a usage error, not a clean pass" \
  2 "$cpe" "$work/preview-malformed.json"
expect_rc "valid JSON that is neither an object nor an array is a usage error" \
  2 "$cpe" "$work/preview-wrong-type.json"
expect_rc "a missing input file is a usage error" \
  2 "$cpe" "$work/does-not-exist.json"
expect_rc "no arguments is a usage error" \
  2 "$cpe"

# A non-web preview legitimately carries no review_url at all — confirm the schema
# never requires one (docs/adapters/PREVIEW.md §Non-web previews).
out=$(jq 'has("review_url")' "$work/preview-package-failed.json")
[ "$out" = "false" ] && ok "the non-web fixture itself carries no review_url (sanity check on the fixture)" \
  || bad "the non-web fixture itself carries no review_url (got has(review_url)=$out)"
echo

# --- 19. .t-workflow/scripts/derive-task-state.sh (issue #147) --------------------------
# The precedence docs/architecture/collaboration-state.md documents. Pure fixtures
# throughout — this script never touches git or the forge, only the small JSON shape
# status-snapshot.sh's own preprocessing (plus /t-status's own procedure) assembles.
echo "derive-task-state.sh"
dts="$root/.t-workflow/scripts/derive-task-state.sh"

mk_ts() { printf '%s' "$1" > "$work/$2"; }

mk_ts '{"blocked":"none","ciState":"pass","review":{"readiness":"ready","current":true},"feedback":{"pendingScopeExpansion":false},"verification":[],"previewReady":null}' ts-awaiting-ship.json
mk_ts '{"blocked":"open","ciState":"pass","review":{"readiness":"ready","current":true},"feedback":{"pendingScopeExpansion":false},"verification":[],"previewReady":null}' ts-blocked-open.json
mk_ts '{"blocked":"cancelled","ciState":"pass","review":{"readiness":"ready","current":true},"feedback":{"pendingScopeExpansion":false},"verification":[],"previewReady":null}' ts-blocked-cancelled.json
mk_ts '{"blocked":"none","ciState":"fail","review":{"readiness":"ready","current":true},"feedback":{"pendingScopeExpansion":false},"verification":[],"previewReady":null}' ts-checks-failing.json
mk_ts '{"blocked":"none","ciState":"pass","review":null,"feedback":{"pendingScopeExpansion":false},"verification":[],"previewReady":null}' ts-awaiting-review.json
mk_ts '{"blocked":"none","ciState":"pass","review":{"readiness":"ready","current":false},"feedback":{"pendingScopeExpansion":false},"verification":[],"previewReady":null}' ts-awaiting-renewed-review.json
mk_ts '{"blocked":"none","ciState":"pass","review":{"readiness":"not-ready","current":true},"feedback":{"pendingScopeExpansion":false},"verification":[],"previewReady":null}' ts-changes-requested.json
mk_ts '{"blocked":"none","ciState":"pass","review":{"readiness":"ready","current":true},"feedback":{"pendingScopeExpansion":true},"verification":[],"previewReady":null}' ts-processing-feedback.json
mk_ts '{"blocked":"none","ciState":"pass","review":{"readiness":"ready","current":true},"feedback":{"pendingScopeExpansion":false},"verification":[{"role":"contributor","required":true,"state":"pending","stale":false}],"previewReady":false}' ts-awaiting-preview.json
mk_ts '{"blocked":"none","ciState":"pass","review":{"readiness":"ready","current":true},"feedback":{"pendingScopeExpansion":false},"verification":[{"role":"contributor","required":true,"state":"pending","stale":false}],"previewReady":null}' ts-awaiting-ext-verif.json
mk_ts '{"blocked":"none","ciState":"pass","review":{"readiness":"ready","current":true},"feedback":{"pendingScopeExpansion":false},"verification":[{"role":"contributor","required":true,"state":"verified","stale":true}],"previewReady":true}' ts-awaiting-ext-verif-stale.json
mk_ts '{"blocked":"none","ciState":"pass","review":{"readiness":"ready","current":true},"feedback":{"pendingScopeExpansion":false},"verification":[{"role":"contributor","required":true,"state":"verified","stale":false}],"previewReady":true}' ts-verified-ready.json
mk_ts '{"blocked":"none","ciState":"pass","review":{"readiness":"ready","current":true},"feedback":{"pendingScopeExpansion":false},"verification":[{"role":"maintainer","required":false,"state":"pending","stale":false}],"previewReady":null}' ts-not-required-never-blocks.json
mk_ts 'not json' ts-malformed.json
mk_ts '{"blocked":"maybe","ciState":"pass","review":null,"feedback":{"pendingScopeExpansion":false},"verification":[],"previewReady":null}' ts-bad-blocked.json

expect_field "no blocker, ready review, no verification: awaiting-ship" \
  .state "awaiting-ship" "$dts" "$work/ts-awaiting-ship.json"
expect_field "an open blocker: blocked" \
  .state "blocked" "$dts" "$work/ts-blocked-open.json"
expect_field "a cancelled blocker: blocked (abandoned, not satisfied)" \
  .state "blocked" "$dts" "$work/ts-blocked-cancelled.json"
expect_field "CI failing: checks-failing" \
  .state "checks-failing" "$dts" "$work/ts-checks-failing.json"
expect_field "no review yet: awaiting-review" \
  .state "awaiting-review" "$dts" "$work/ts-awaiting-review.json"
expect_field "a review that predates the head commit: awaiting-renewed-review" \
  .state "awaiting-renewed-review" "$dts" "$work/ts-awaiting-renewed-review.json"
expect_field "current review reads not-ready: changes-requested" \
  .state "changes-requested" "$dts" "$work/ts-changes-requested.json"
expect_field "a feedback entry proposed scope expansion: processing-feedback" \
  .state "processing-feedback" "$dts" "$work/ts-processing-feedback.json"
expect_field "a pending required entry with preview evidence not ready: awaiting-preview" \
  .state "awaiting-preview" "$dts" "$work/ts-awaiting-preview.json"
expect_field "a pending required entry with no preview evidence at all: awaiting-external-verification" \
  .state "awaiting-external-verification" "$dts" "$work/ts-awaiting-ext-verif.json"
expect_field "a stale verified entry: still awaiting-external-verification (preview not the reason)" \
  .state "awaiting-external-verification" "$dts" "$work/ts-awaiting-ext-verif-stale.json"
expect_field "a stale verified entry names staleness in its detail" \
  .detail "a later commit could affect a previously verified/risk-accepted entry — it must be re-verified or re-accepted at the new revision" \
  "$dts" "$work/ts-awaiting-ext-verif-stale.json"
expect_field "every required verification entry resolved and current: verified-ready-for-ship" \
  .state "verified-ready-for-ship" "$dts" "$work/ts-verified-ready.json"
expect_field "a not-required pending entry never blocks: still reaches verified-ready-for-ship" \
  .state "verified-ready-for-ship" "$dts" "$work/ts-not-required-never-blocks.json"
expect_rc "malformed JSON is a usage error, not a clean classification" \
  2 "$dts" "$work/ts-malformed.json"
expect_rc "an unrecognized \`blocked\` value is a usage error" \
  2 "$dts" "$work/ts-bad-blocked.json"
expect_rc "a missing input file is a usage error" \
  2 "$dts" "$work/does-not-exist.json"
expect_rc "no arguments is a usage error" \
  2 "$dts"

# Precedence: two conditions that would each independently classify differently — the
# earlier one in docs/architecture/collaboration-state.md's own table must win.
mk_ts '{"blocked":"open","ciState":"fail","review":null,"feedback":{"pendingScopeExpansion":true},"verification":[{"role":"contributor","required":true,"state":"pending","stale":false}],"previewReady":false}' ts-precedence-blocked-wins.json
expect_field "blocked outranks failing CI, no review, feedback, and verification all at once" \
  .state "blocked" "$dts" "$work/ts-precedence-blocked-wins.json"
mk_ts '{"blocked":"none","ciState":"fail","review":null,"feedback":{"pendingScopeExpansion":true},"verification":[{"role":"contributor","required":true,"state":"pending","stale":false}],"previewReady":false}' ts-precedence-ci-wins.json
expect_field "failing CI outranks no review, feedback, and verification" \
  .state "checks-failing" "$dts" "$work/ts-precedence-ci-wins.json"
mk_ts '{"blocked":"none","ciState":"pass","review":{"readiness":"not-ready","current":false},"feedback":{"pendingScopeExpansion":true},"verification":[{"role":"contributor","required":true,"state":"pending","stale":false}],"previewReady":false}' ts-precedence-renewed-wins.json
expect_field "a stale not-ready review reads as awaiting-renewed-review, not changes-requested" \
  .state "awaiting-renewed-review" "$dts" "$work/ts-precedence-renewed-wins.json"
mk_ts '{"blocked":"none","ciState":"pass","review":{"readiness":"not-ready","current":true},"feedback":{"pendingScopeExpansion":true},"verification":[{"role":"contributor","required":true,"state":"pending","stale":false}],"previewReady":false}' ts-precedence-changes-wins.json
expect_field "changes-requested outranks a pending feedback item and unresolved verification" \
  .state "changes-requested" "$dts" "$work/ts-precedence-changes-wins.json"
mk_ts '{"blocked":"none","ciState":"pass","review":{"readiness":"ready","current":true},"feedback":{"pendingScopeExpansion":true},"verification":[{"role":"contributor","required":true,"state":"pending","stale":false}],"previewReady":false}' ts-precedence-feedback-wins.json
expect_field "processing-feedback outranks unresolved verification once review is clean" \
  .state "processing-feedback" "$dts" "$work/ts-precedence-feedback-wins.json"
echo

# --- 20. .t-workflow/scripts/parse-task-record.sh (issue #147) ---------------------------
# Turns a task record's ## Verification/## Feedback markdown, plus an issue body's ##
# Plan -> verification: scope list, into JSON. Pure fixtures — text files in, JSON out,
# no git or forge call of its own.
echo "parse-task-record.sh"
ptr="$root/.t-workflow/scripts/parse-task-record.sh"

cat > "$work/record-none.md" <<'EOF'
# 999 — Test record
Issue: #999

## Verification
none — this task declares no `verification:` entry of its own.

## Feedback
none — no feedback pass has run against this task itself.
EOF

cat > "$work/record-no-feedback-heading.md" <<'EOF'
# 999 — Test record
Issue: #999

## Verification
none — this task declares no `verification:` entry of its own.

## Decisions made along the way
- none
EOF

cat > "$work/record-populated.md" <<'EOF'
# 999 — Test record
Issue: #999

## Verification
- role: contributor — required: true
  what: exercised the preview and confirmed the login flow works
  state: verified
  evidence: clicked through the preview — revision: `a1b2c3d4e5f60718293a4b5c6d7e8f9012345678`
  by: Jane — date: 2026-09-10
- role: maintainer — required: false
  what: sanity-checked the copy
  state: pending
  evidence: awaiting — revision: `none yet`
  by: — date: —

## Feedback
- reference: https://preview.example.com/comment/1
  source: a contributor
  classification: defect
  response: fixed the off-by-one in the pagination
  by: someone — date: 2026-09-10
- reference: https://preview.example.com/comment/2
  source: a maintainer
  classification: proposed scope expansion
  response: stopped; awaiting human authorization and /t-plan
  by: someone — date: 2026-09-11
EOF

cat > "$work/issue-with-scope.md" <<'EOF'
## Plan
### Allowed paths
- `some/path.md`

### Validation
agent_checks:
  - `echo ok` — stage: implementation — proves: nothing
verification:
  - role: contributor
    required: true
    scope: docs/foo/*, docs/bar.md
  - role: maintainer
    required: false
EOF

expect_field "a 'none'/'none' record: empty verification array" \
  '.verification | length' "0" "$ptr" "$work/record-none.md"
expect_field "a 'none'/'none' record: null feedback classification" \
  .feedbackLastClassification "null" "$ptr" "$work/record-none.md"
expect_field "a record predating the ## Feedback convention: null classification, not an error" \
  .feedbackLastClassification "null" "$ptr" "$work/record-no-feedback-heading.md"
expect_field "a populated record: two verification entries" \
  '.verification | length' "2" "$ptr" "$work/record-populated.md"
expect_field "the first entry's role" \
  '.verification[0].role' "contributor" "$ptr" "$work/record-populated.md"
expect_field "the first entry's state" \
  '.verification[0].state' "verified" "$ptr" "$work/record-populated.md"
expect_field "the first entry's revision" \
  '.verification[0].revision' "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678" "$ptr" "$work/record-populated.md"
expect_field "a 'none yet' revision reads as null, never a literal string" \
  '.verification[1].revision' "null" "$ptr" "$work/record-populated.md"
expect_field "the not-required second entry's required flag" \
  '.verification[1].required' "false" "$ptr" "$work/record-populated.md"
expect_field "only the LAST feedback entry's classification is reported" \
  .feedbackLastClassification "proposed scope expansion" "$ptr" "$work/record-populated.md"
expect_field "with no issue-body file, every entry's scope is empty (no scope known yet)" \
  '.verification[0].scope | length' "0" "$ptr" "$work/record-populated.md"

expect_field "given the issue body, scope globs are matched in by role" \
  '.verification[0].scope | join(",")' "docs/foo/*,docs/bar.md" "$ptr" "$work/record-populated.md" "$work/issue-with-scope.md"
expect_field "a role with no scope: in the plan reads as an empty scope list" \
  '.verification[1].scope | length' "0" "$ptr" "$work/record-populated.md" "$work/issue-with-scope.md"

expect_rc "a missing record file is a usage error" \
  2 "$ptr" "$work/does-not-exist.md"
expect_rc "no arguments is a usage error" \
  2 "$ptr"
expect_rc "an existing record with a non-existent issue-body file is a usage error" \
  2 "$ptr" "$work/record-none.md" "$work/does-not-exist.md"
echo

# --- 21. .t-workflow/scripts/status-snapshot.sh --test-stale-match (issue #147) ---------
# The exact glob-matching rule status-snapshot.sh's own staleness computation delegates
# to (docs/architecture/verification.md's formula: stale UNLESS the diff touches NONE
# of the declared scope globs). Pure fixtures — no git diff, no live PR — this is what
# a /t-review pass on this task's own PR found untested when it caught the rule's two
# branches swapped (a diff touching a declared scope glob read as *not* stale).
echo "status-snapshot.sh --test-stale-match"
ssm="$root/.t-workflow/scripts/status-snapshot.sh"

expect_match() {
  local desc="$1" want="$2" changed="$3"; shift 3
  local got
  got=$(printf '%s\n' "$changed" | "$ssm" --test-stale-match "$@")
  if [ "$got" = "$want" ]; then ok "$desc (got $got)"; else bad "$desc (want $want, got $got)"; fi
}

expect_match "a changed path matching a declared scope glob: stale (true)" \
  "true" "docs/foo/bar.md" "docs/foo/*"
expect_match "a changed path matching none of the declared scope globs: not stale (false)" \
  "false" "src/unrelated.js" "docs/foo/*" "docs/bar.md"
expect_match "one of several changed paths matches: stale (true)" \
  "true" "$(printf '%s\n' "src/unrelated.js" "docs/foo/deep/file.md")" "docs/foo/*"
expect_match "no changed paths at all: not stale (false — nothing could have affected it)" \
  "false" "" "docs/foo/*"
expect_match "called with zero glob arguments: matches nothing, reads not stale (false) -- status-snapshot.sh itself never calls this hook that way; its own separate \"no scope declared at all\" branch defaults to stale before ever reaching here" \
  "false" "docs/foo/bar.md"
echo

# --- 22. .t-workflow/scripts/check-observer-marker.sh (issue #143) ---------------------
# Validates the single-line HTML-comment correlation marker /t-open appends to a new
# issue's body (docs/adapters/OBSERVER.md). Pure fixtures — text files in, JSON out, no
# git or forge call of its own.
echo "check-observer-marker.sh"
com="$root/.t-workflow/scripts/check-observer-marker.sh"

mk_marker() { printf '%s\n' "$1" > "$work/$2"; }

mk_marker 'Some issue body with no marker at all.' marker-none.md
mk_marker 'body
<!-- t-workflow:v1 task=143 -->' marker-task-only.md
mk_marker 'body
<!-- t-workflow:v1 task=143 parent=139 origin-system="Proposarium Labs" origin-url=https://proposarium.example/p/42 -->' marker-task-full.md
mk_marker 'body
<!-- t-workflow:v1 initiative=139 -->' marker-init-only.md
mk_marker '<!-- t-workflow:v1 task=143 initiative=139 -->' marker-both.md
mk_marker '<!-- t-workflow:v1 parent=1 -->' marker-neither.md
mk_marker '<!-- t-workflow:v1 task=143 origin-system="X" -->' marker-unpaired-system.md
mk_marker '<!-- t-workflow:v1 task=143 origin-url=https://x.example -->' marker-unpaired-url.md
mk_marker '<!-- t-workflow:v1 initiative=139 parent=1 -->' marker-parent-on-init.md
mk_marker '<!-- t-workflow:v1 task=abc -->' marker-nonnumeric.md
mk_marker '<!-- t-workflow:v1 task=143 bogus=1 -->' marker-bogus-key.md
mk_marker '<!-- t-workflow:v1 task=143 -->
<!-- t-workflow:v1 task=144 -->' marker-multiple.md

expect_field "no marker present: present=false" \
  .present "false" "$com" "$work/marker-none.md"
expect_field "no marker present: still reads valid (nothing to check)" \
  .valid "true" "$com" "$work/marker-none.md"
expect_rc "no marker present: exit 0" \
  0 "$com" "$work/marker-none.md"

expect_field "task-only marker: present, valid, task extracted" \
  .task "143" "$com" "$work/marker-task-only.md"
expect_rc "task-only marker: exit 0" \
  0 "$com" "$work/marker-task-only.md"

expect_field "task+parent+origin marker: task extracted" \
  .task "143" "$com" "$work/marker-task-full.md"
expect_field "task+parent+origin marker: parent extracted" \
  .parent "139" "$com" "$work/marker-task-full.md"
expect_field "task+parent+origin marker: origin system extracted (quoted value with spaces)" \
  .origin.system "Proposarium Labs" "$com" "$work/marker-task-full.md"
expect_field "task+parent+origin marker: origin url extracted (bare value)" \
  .origin.url "https://proposarium.example/p/42" "$com" "$work/marker-task-full.md"
expect_rc "task+parent+origin marker: exit 0" \
  0 "$com" "$work/marker-task-full.md"

expect_field "initiative-only marker: initiative extracted, task null" \
  .initiative "139" "$com" "$work/marker-init-only.md"
expect_rc "initiative-only marker: exit 0" \
  0 "$com" "$work/marker-init-only.md"

expect_rc "both task and initiative present: fails" \
  1 "$com" "$work/marker-both.md"
expect_rc "neither task nor initiative present: fails" \
  1 "$com" "$work/marker-neither.md"
expect_rc "origin-system without origin-url: fails" \
  1 "$com" "$work/marker-unpaired-system.md"
expect_rc "origin-url without origin-system: fails" \
  1 "$com" "$work/marker-unpaired-url.md"
expect_field "an invalid (unpaired) marker reports origin as null, never half-formed" \
  .origin "null" "$com" "$work/marker-unpaired-system.md"
expect_rc "parent alongside initiative: fails" \
  1 "$com" "$work/marker-parent-on-init.md"
expect_rc "a non-numeric task id: fails" \
  1 "$com" "$work/marker-nonnumeric.md"
expect_rc "an unrecognized key: fails" \
  1 "$com" "$work/marker-bogus-key.md"
expect_rc "more than one marker line: fails" \
  1 "$com" "$work/marker-multiple.md"

expect_field "an invalid marker still reports present=true (distinct from absent)" \
  .present "true" "$com" "$work/marker-both.md"
expect_field "an invalid marker's errors[] is non-empty" \
  '.errors | length > 0' "true" "$com" "$work/marker-both.md"

expect_rc "a missing input file is a usage error" \
  2 "$com" "$work/does-not-exist.md"
expect_rc "no arguments is a usage error" \
  2 "$com"
echo

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
