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

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
