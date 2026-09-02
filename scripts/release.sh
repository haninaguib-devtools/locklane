#!/usr/bin/env bash
# The mechanical steps of cutting a release, for /l-release (#602, ADR-109 D2).
#
# `.claude/skills/l-release/SKILL.md` calls these two subcommands instead of spelling
# their steps out, so the checks run the same way every time. Each exits non-zero with
# one plain line on stderr at the first failure and creates nothing on failure.
#
#   gate <version>
#     Before any write: the root pom.xml <revision> equals <version>-SNAPSHOT, no
#     v<version> tag exists on origin, and no v<version> release exists.
#
#   dispatch <version>
#     After the release task's PR has merged: runs .github/workflows/release.yml on
#     main with the version input, watches that run to its conclusion, then confirms
#     release v<version> exists and its body equals the CHANGELOG.md section on
#     origin/main (`scripts/generate-release-notes.sh extract`). Refuses to dispatch
#     while another Release run is queued or in progress — release.yml's concurrency
#     group would cancel it.
set -uo pipefail

die() { echo "release: $*" >&2; exit 1; }

usage() {
  sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

mode="${1:-}"
version="${2:-}"
[ -n "$mode" ] && [ -n "$version" ] || usage
printf '%s' "$version" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$' \
  || die "version '$version' is not a bare X.Y.Z (no v prefix, no suffix)"
tag="v${version}"

# `gh release view` exits 1 both for "no such release" and for a failed query; only the
# former means the version is free.
release_exists() {
  local out
  if out=$(gh release view "$tag" 2>&1); then
    return 0
  fi
  printf '%s' "$out" | grep -qi 'release not found' && return 1
  die "could not query releases for $tag: $(printf '%s' "$out" | head -1)"
}

tag_exists_on_origin() {
  local out
  out=$(git ls-remote --tags origin "refs/tags/${tag}" 2>&1) \
    || die "could not read tags from origin: $(printf '%s' "$out" | head -1)"
  [ -n "$out" ]
}

# Trailing whitespace, CR, and trailing blank lines are presentation, not content:
# GitHub normalizes a release body's line endings, and the extract output ends without
# the trailing blank line the file keeps between sections.
normalize() {
  tr -d '\r' | sed 's/[[:space:]]*$//' | awk '{ l[NR] = $0 } END {
    n = NR; while (n > 0 && l[n] == "") n--; for (i = 1; i <= n; i++) print l[i] }'
}

case "$mode" in
  gate)
    [ -f pom.xml ] || die "no pom.xml in $(pwd) — run from the repository root"
    if tag_exists_on_origin; then
      die "tag $tag already exists on origin — a released version is immutable; bump <revision> and cut a new version"
    fi
    if release_exists; then
      die "release $tag already exists — a released version is immutable; bump <revision> and cut a new version"
    fi
    revision=$(sed -n 's/.*<revision>\(.*\)<\/revision>.*/\1/p' pom.xml | head -1)
    [ -n "$revision" ] || die "pom.xml has no <revision> element"
    [ "$revision" = "${version}-SNAPSHOT" ] \
      || die "pom.xml <revision> is '$revision', expected '${version}-SNAPSHOT' to cut $version"
    echo "OK: <revision> is ${revision}; no ${tag} tag or release exists"
    ;;

  dispatch)
    [ -x scripts/generate-release-notes.sh ] \
      || die "scripts/generate-release-notes.sh not found — run from the repository root"
    busy=$(gh run list --workflow release.yml --limit 10 \
      --json databaseId,status --jq '[.[] | select(.status != "completed")] | length' 2>/dev/null) \
      || die "could not list Release runs"
    [ "$busy" = "0" ] || die "a Release run is already queued or in progress — wait for it before dispatching $tag"

    before=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    gh workflow run release.yml -f "version=${version}" >/dev/null \
      || die "could not dispatch release.yml with version=${version}"

    run_id=""
    for _ in $(seq 1 30); do
      run_id=$(gh run list --workflow release.yml --event workflow_dispatch --limit 5 \
        --json databaseId,createdAt --jq --arg t "$before" \
        '[.[] | select(.createdAt >= $t)] | sort_by(.createdAt) | last | .databaseId // empty' 2>/dev/null)
      [ -n "$run_id" ] && break
      sleep 2
    done
    [ -n "$run_id" ] || die "dispatched release.yml but no run appeared within 60 s — check the Actions list"
    url=$(gh run view "$run_id" --json url --jq .url 2>/dev/null || echo "run $run_id")
    echo "Watching Release run $url"

    if ! gh run watch "$run_id" --exit-status >/dev/null 2>&1; then
      conclusion=$(gh run view "$run_id" --json conclusion --jq .conclusion 2>/dev/null || echo unknown)
      die "Release run concluded '$conclusion' — $url"
    fi

    body=$(gh release view "$tag" --json body --jq .body 2>&1) \
      || die "Release run succeeded but release $tag cannot be read: $(printf '%s' "$body" | head -1)"

    git fetch -q origin main || die "could not fetch origin/main to compare the release body"
    changelog=$(mktemp)
    trap 'rm -f "$changelog"' EXIT
    git show origin/main:CHANGELOG.md > "$changelog" || die "origin/main has no CHANGELOG.md"
    expected=$(./scripts/generate-release-notes.sh extract --version "$version" --changelog "$changelog") \
      || die "CHANGELOG.md on origin/main has no section for ${version}"

    if ! diff <(printf '%s\n' "$body" | normalize) <(printf '%s\n' "$expected" | normalize) >/dev/null; then
      die "release $tag body does not equal the CHANGELOG.md section on origin/main"
    fi
    echo "OK: release $tag published — $(gh release view "$tag" --json url --jq .url 2>/dev/null); body equals the CHANGELOG.md section on origin/main"
    ;;

  *)
    usage
    ;;
esac
