#!/usr/bin/env bash
# Print the trunk branch name: origin's default branch, else main/master/trunk if one
# exists locally, else "main".
set -uo pipefail
ref=$(git symbolic-ref -q --short refs/remotes/origin/HEAD 2>/dev/null) && [ -n "$ref" ] && { echo "${ref#origin/}"; exit 0; }
r=$(git remote show origin 2>/dev/null | sed -n 's/.*HEAD branch: //p' | head -1)
[ -n "$r" ] && [ "$r" != "(unknown)" ] && { echo "$r"; exit 0; }
for b in main master trunk; do
  git show-ref -q --verify "refs/heads/$b" && { echo "$b"; exit 0; }
done
echo main
