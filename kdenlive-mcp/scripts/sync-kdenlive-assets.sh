#!/usr/bin/env bash
# Copies Kdenlive's own effect / transition / generator definitions (data/ in the Kdenlive tree, CC0-1.0)
# into src/main/resources/kdenlive-assets, plus an index file, so the server knows every asset Kdenlive offers.
#
#   scripts/sync-kdenlive-assets.sh                 # from ../data (this repo is a Kdenlive fork)
#   scripts/sync-kdenlive-assets.sh /path/to/kdenlive/data
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="${1:-$HERE/../data}"
DEST="$HERE/src/main/resources/kdenlive-assets"
[ -d "$SRC/effects" ] || { echo "no Kdenlive data dir at $SRC" >&2; exit 1; }

rm -rf "$DEST"
mkdir -p "$DEST"
cd "$SRC"
find effects transitions generators -name '*.xml' | LC_ALL=C sort | while read -r f; do
  mkdir -p "$DEST/$(dirname "$f")"
  cp "$f" "$DEST/$f"
done
cp kdenliveeffectscategory.rc hidden_effects.txt hidden_transitions.txt excluded_effects.txt excluded_transitions.txt "$DEST/"
(cd "$DEST" && find effects transitions generators -name '*.xml' | LC_ALL=C sort > index.txt)
git -C "$SRC" rev-parse --short HEAD > "$DEST/SOURCE_REVISION" 2>/dev/null || echo unknown > "$DEST/SOURCE_REVISION"
cat > "$DEST/README.md" <<EOF
Copied from Kdenlive's data/ directory by scripts/sync-kdenlive-assets.sh (revision $(cat "$DEST/SOURCE_REVISION")).
Kdenlive licenses these definitions as CC0-1.0. Do not edit by hand; re-run the script instead.
EOF
echo "synced $(wc -l < "$DEST/index.txt" | tr -d ' ') asset definitions into $DEST"
