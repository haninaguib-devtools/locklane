#!/usr/bin/env bash
# Curated release notes for CHANGELOG.md and the release body (#464,
# docs/architecture/releasing.md § Release notes).
#
# Two modes, one script, so generation and extraction cannot drift:
#
#   generate --version X.Y.Z [--prev <tag>] [--date YYYY-MM-DD] [--changelog <file>]
#     Collects the first-parent squash subjects since the previous release tag
#     (default: the newest v* tag reachable from HEAD), parses each as
#     `[<id>] <title> (#<pr>)`, reads the issue's classification label from the
#     tracker (`gh issue view`), groups the entries — bug → Fixes, enhancement →
#     Features, documentation → Documentation, anything else → Other; a subject
#     that does not parse is listed under Other verbatim, never dropped — and
#     inserts the new `## vX.Y.Z — <date>` section at the top of CHANGELOG.md
#     (newest first). The date is an explicit input defaulting to today, so the
#     same inputs always produce the same notes. Refuses to generate a section
#     that already exists. This mode runs in a task session (it needs git history
#     and an authenticated `gh`); its output lands on `main` through an ordinary
#     notes PR *before* the release is dispatched.
#
#   extract --version X.Y.Z [--changelog <file>]
#     Prints that version's CHANGELOG.md section, heading included, exactly as
#     committed (trailing blank lines trimmed). Exits 1 when no such section
#     exists. `.github/workflows/release.yml` runs this — and only this — at cut
#     time, so the release body equals the committed section by construction.
#     Needs no network and no tracker access.
set -euo pipefail

err() { echo "generate-release-notes: $*" >&2; exit 1; }

usage() {
  sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

mode="${1:-}"
[ -n "$mode" ] || usage
shift

version="" prev="" date="" changelog="CHANGELOG.md"
while [ $# -gt 0 ]; do
  case "$1" in
    --version)   version="${2:?}"; shift 2 ;;
    --prev)      prev="${2:?}"; shift 2 ;;
    --date)      date="${2:?}"; shift 2 ;;
    --changelog) changelog="${2:?}"; shift 2 ;;
    *) usage ;;
  esac
done
[ -n "$version" ] || err "--version is required"

case "$mode" in
  extract)
    [ -f "$changelog" ] || err "$changelog not found"
    # Literal prefix match (no regex — the version contains dots); the space
    # after the version keeps v0.2.0 from matching a v0.2.01 heading. Command
    # substitution trims the trailing blank line(s) that separate sections.
    section=$(awk -v ver="$version" '
      insec && index($0, "## v") == 1 { exit }
      index($0, "## v" ver " ") == 1 { insec = 1 }
      insec { print }
    ' "$changelog")
    [ -n "$section" ] || err "no section for version ${version} in ${changelog}"
    printf '%s\n' "$section"
    ;;

  generate)
    [ -f "$changelog" ] || err "$changelog not found"
    ! grep -q "^## v${version} " "$changelog" \
      || err "section for version ${version} already exists in ${changelog}"
    [ -n "$date" ] || date=$(date +%Y-%m-%d)

    if [ -z "$prev" ]; then
      prev=$(git tag --list 'v*' --merged HEAD --sort=-v:refname | head -n 1)
    fi
    if [ -n "$prev" ]; then
      range="${prev}..HEAD"
    else
      # No release tag reachable: the very first cut covers all of history.
      range="HEAD"
    fi

    features=() fixes=() docs=() other=()
    count=0
    while IFS= read -r subject; do
      count=$((count + 1))
      if [[ "$subject" =~ ^\[([0-9]+)\]\ (.+)\ \(#([0-9]+)\)$ ]]; then
        id="${BASH_REMATCH[1]}" title="${BASH_REMATCH[2]}" pr="${BASH_REMATCH[3]}"
        labels=$(gh issue view "$id" --json labels --jq '[.labels[].name] | join(",")') \
          || err "could not read labels of issue #${id} (from '${subject}')"
        entry="- ${title} (#${id}, #${pr})"
        # An issue carries exactly one classification label (/t-open); the fixed
        # order below is only a determinism tiebreak should one ever carry more.
        case ",${labels}," in
          *,bug,*)           fixes+=("$entry") ;;
          *,enhancement,*)   features+=("$entry") ;;
          *,documentation,*) docs+=("$entry") ;;
          *)                 other+=("$entry") ;;
        esac
      else
        other+=("- ${subject}")
      fi
    done < <(git log --first-parent --format=%s "$range")
    [ "$count" -gt 0 ] || err "no commits in ${range} — nothing to release"

    section="## v${version} — ${date}"$'\n'
    emit_group() {
      local name="$1"; shift
      [ $# -gt 0 ] || return 0
      section+=$'\n'"### ${name}"$'\n'
      local e
      for e in "$@"; do section+="${e}"$'\n'; done
    }
    emit_group "Features"      ${features[@]+"${features[@]}"}
    emit_group "Fixes"         ${fixes[@]+"${fixes[@]}"}
    emit_group "Documentation" ${docs[@]+"${docs[@]}"}
    emit_group "Other"         ${other[@]+"${other[@]}"}

    # Newest first: the new section goes above the first existing one, or at the
    # end of the header when none exists yet.
    first=$(grep -n '^## v' "$changelog" | head -n 1 | cut -d: -f1 || true)
    tmp=$(mktemp)
    if [ -n "$first" ]; then
      {
        head -n "$((first - 1))" "$changelog"
        printf '%s\n' "$section"
        tail -n "+${first}" "$changelog"
      } > "$tmp"
    else
      {
        cat "$changelog"
        printf '\n%s' "$section"
      } > "$tmp"
    fi
    mv "$tmp" "$changelog"
    echo "generate-release-notes: wrote section v${version} (${range}, ${count} commit(s)) to ${changelog}" >&2
    ;;

  *) usage ;;
esac
