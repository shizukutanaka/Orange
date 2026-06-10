#!/usr/bin/env bash
# Mirror the working tree into the outputs directory, exactly.
#
# WHY THIS EXISTS
# Hand-written `find | cp` loops have bitten us: a partial sync once left the
# outputs directory with a stale PhoneNumbers.kt (old normalize) while the docs
# and CHANGELOG referenced a fix that wasn't actually in the shipped source.
# This script makes the mirror deterministic and complete: it deletes files in
# the destination that no longer exist in the source, so renames and deletions
# propagate correctly.
#
# Usage: bash tools/sync_output.sh [SRC] [DEST]
set -euo pipefail

SRC="${1:-.}"
DEST="${2:-/mnt/user-data/outputs/orange}"

# Safety guard: this script does `rm -rf "$DEST"` in the no-rsync path and
# `rsync --delete` in the rsync path. Both are destructive to DEST. To prevent
# a swapped-argument accident (e.g. `sync_output.sh OUTPUT WORKTREE`) from
# wiping the working tree, refuse to run unless DEST is clearly an outputs
# directory. Override deliberately by passing an explicit DEST under outputs.
case "$DEST" in
    */user-data/outputs/*|*/outputs/orange|*/outputs/orange/) : ;;
    *)
        echo "REFUSING: destination '$DEST' is not an outputs directory." >&2
        echo "Pass the outputs path as the 2nd argument, source as the 1st." >&2
        exit 2
        ;;
esac

if command -v rsync >/dev/null 2>&1; then
    mkdir -p "$DEST"
    rsync -a --delete \
        --exclude '.git/' \
        --exclude 'build/' \
        --exclude '.gradle/' \
        "$SRC"/ "$DEST"/
else
    # rsync unavailable: rebuild destination from scratch (still complete).
    rm -rf "$DEST"
    mkdir -p "$DEST"
    (cd "$SRC" && find . -type f \
        ! -path '*/.git/*' ! -path '*/build/*' ! -path '*/.gradle/*' \
        -print0) | while IFS= read -r -d '' f; do
        mkdir -p "$DEST/$(dirname "$f")"
        cp "$SRC/$f" "$DEST/$f"
    done
fi

echo "Synced $(find "$DEST" -type f | wc -l) files to $DEST"
