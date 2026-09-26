#!/usr/bin/env bash
# Usage: release-notes.sh <stable|nightly> <version>
# Prints the commit list for a release body, newest first.
set -euo pipefail

usage() {
  echo "usage: $(basename "$0") <stable|nightly> <version>" >&2
  exit 2
}

[ "$#" -eq 2 ] || usage
mode=$1
version=$2
case "$mode" in
  stable|nightly) ;;
  *) usage ;;
esac

# Only X.Y.Z tags are releases of this repo; nightly and piped-* are not.
stable_tags() {
  git tag -l --sort=-v:refname | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' || true
}

# Highest stable tag below the target version, ignoring history: an old tag can
# sit off the current branch after a rewrite.
prev_stable() {
  local target=$1 tag
  while read -r tag; do
    [ -n "$tag" ] || continue
    [ "$tag" != "$target" ] || continue
    if [ "$(printf '%s\n%s\n' "$tag" "$target" | sort -V | head -1)" = "$tag" ]; then
      printf '%s\n' "$tag"
      return 0
    fi
  done < <(stable_tags)
}

if [ "$mode" = stable ]; then
  prev=$(prev_stable "$version")
else
  prev=$(stable_tags | head -1)
fi

if [ -n "$prev" ]; then
  # --cherry-pick drops commits that the tag side already has under another hash,
  # which is what a rewrite leaves behind.
  notes=$(git log --no-merges --right-only --cherry-pick --format='- %s (%h)' "$prev...HEAD")
else
  notes=$(git log --no-merges --format='- %s (%h)' HEAD)
fi

if [ -n "$notes" ]; then
  printf '%s\n' "$notes"
elif [ -n "$prev" ]; then
  echo "No changes since $prev."
else
  echo "No commits found."
fi
