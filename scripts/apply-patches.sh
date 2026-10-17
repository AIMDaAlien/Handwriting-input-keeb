#!/usr/bin/env bash
# Apply patches/*.patch to the pinned upstream/ working tree. Idempotent: a patch that is
# already applied is skipped, one that conflicts stops the script.
#
# The submodule commit never changes; edits live only in upstream's working tree.
# To change a patch: edit files under upstream/, then  scripts/apply-patches.sh --save
# which rewrites patches/futo-handwriting.patch from `git -C upstream diff`.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UP="$ROOT/upstream"
PATCH="$ROOT/patches/futo-handwriting.patch"

if [[ "${1:-}" == "--save" ]]; then
  mkdir -p "$(dirname "$PATCH")"
  git -C "$UP" diff > "$PATCH"
  echo "saved $(grep -c '^diff --git' "$PATCH") file(s) to ${PATCH#$ROOT/}"
  exit 0
fi

for p in "$ROOT"/patches/*.patch; do
  name="${p#$ROOT/}"
  if git -C "$UP" apply --check --reverse "$p" 2>/dev/null; then
    echo "already applied: $name"
  elif git -C "$UP" apply --check "$p" 2>/dev/null; then
    git -C "$UP" apply "$p"
    echo "applied: $name"
  else
    echo "CONFLICT: $name does not apply to upstream $(git -C "$UP" describe --tags --always)" >&2
    git -C "$UP" apply --check "$p" || true
    exit 1
  fi
done
