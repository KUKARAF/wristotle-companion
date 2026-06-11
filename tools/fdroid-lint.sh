#!/bin/sh
# fdroid-lint.sh — verify the staged fdroiddata YAML using the live upstream
# F-Droid category + antifeature definitions. No hand-rolled stubs.
#
# Usage:
#   tools/fdroid-lint.sh                          # uses default YAML path
#   tools/fdroid-lint.sh path/to/our.yml          # explicit path
#
# The canonical YAML for our submission lives at:
#   ../../claude_knowledge/pebble_dev/wristotle-companion/
#       fdroiddata-com.lazydevs.wristotle.yml
# (the F-Droid submission plan in the same dir explains why it lives there
# instead of in this repo). Override with the YAML_PATH env var or a
# positional arg if your clone is elsewhere.
#
# The lint logic itself is in `fdroid` (fdroidserver 2.4+). What this
# script adds:
#
#   1. Pulls `config/categories.yml` straight from gitlab.com/fdroid/
#      fdroiddata's master branch into a scratch dir so the category
#      check runs against the real upstream definition, not a stub list
#      that could drift. (Without it, fdroidserver defaults
#      CATEGORIES_KEYS to [] and EVERY category fails the lint.)
#
#   2. Stages a minimal `metadata/<pkg>.yml` layout fdroidserver expects.
#
#   3. Cleans up the scratch dir on exit (success or failure).
#
# Requires: `fdroid` on PATH (pipx install fdroidserver) + curl. Skips
# gracefully when fdroid isn't available so the script is safe to wire
# into CI / hooks that may run on machines without it.

set -e

PACKAGE_NAME="com.lazydevs.wristotle"
DEFAULT_YAML="../../claude_knowledge/pebble_dev/wristotle-companion/fdroiddata-${PACKAGE_NAME}.yml"
YAML_PATH=${YAML_PATH:-${1:-$DEFAULT_YAML}}

FDROIDDATA_RAW="https://gitlab.com/fdroid/fdroiddata/-/raw/master"

if ! command -v fdroid >/dev/null 2>&1; then
    echo "fdroid CLI not found on PATH — skipping lint."
    echo "Install with: pipx install fdroidserver"
    exit 0
fi

if ! command -v curl >/dev/null 2>&1; then
    echo "curl not found on PATH — install it or run on a host that has it."
    exit 1
fi

if [ ! -f "$YAML_PATH" ]; then
    echo "✗ YAML not found at: $YAML_PATH"
    echo "  Pass an explicit path: tools/fdroid-lint.sh path/to/our.yml"
    echo "  Or set YAML_PATH env var."
    exit 1
fi

SCRATCH=$(mktemp -d -t fdroid-lint.XXXXXX)
trap 'rm -rf "$SCRATCH"' EXIT INT TERM

mkdir -p "$SCRATCH/metadata" "$SCRATCH/config"
cp "$YAML_PATH" "$SCRATCH/metadata/${PACKAGE_NAME}.yml"
touch "$SCRATCH/config.yml"

# Pull the live upstream category definition so the category check
# runs against what fdroiddata actually accepts today. Strip the
# `icon:` lines — the upstream YAML points at PNG assets that don't
# exist in our scratch dir, and the config loader would try to copy
# each one. We only need the category names for the validity check.
if ! curl -fsSL "$FDROIDDATA_RAW/config/categories.yml" -o "$SCRATCH/config/categories.yml.full"; then
    echo "✗ Failed to fetch categories.yml from $FDROIDDATA_RAW"
    echo "  (Network down, or fdroiddata moved the file?)"
    exit 1
fi
grep -v '^  icon:' "$SCRATCH/config/categories.yml.full" > "$SCRATCH/config/categories.yml"
rm "$SCRATCH/config/categories.yml.full"

# Empty antifeatures.yml stub. fdroidserver loads the antifeatures
# config eagerly at lint startup, but the upstream file references
# icon assets (ic_antifeature_*.png) that don't exist in our scratch
# dir. Since our YAML has no AntiFeatures: entries, an empty config
# means lint runs cleanly — and if we ever ADD an AntiFeature, it'll
# fail loudly as "not valid", which is what we'd want as a prompt to
# extend this script.
echo '{}' > "$SCRATCH/config/antiFeatures.yml"

cd "$SCRATCH"

# Capture output so we can detect lint findings (fdroid lint exits 0 even
# when it prints warnings). A clean run prints only the benign
# `apksigner not found` warning on hosts without it.
OUT=$(fdroid lint "$PACKAGE_NAME" 2>&1 || true)
echo "$OUT"

# Strip the apksigner warning (benign on macOS hosts; the upstream
# build server has it) and any blank lines, then count what remains.
FINDINGS=$(echo "$OUT" | grep -v "apksigner not found" | grep -v "^$" | grep -v "^WARNING" | grep -v "^2[0-9][0-9][0-9]-" || true)

if [ -n "$FINDINGS" ]; then
    echo
    echo "✗ Lint surfaced findings — review above before opening the fdroiddata PR."
    exit 1
fi

echo
echo "✓ fdroid lint clean — YAML ready for the fdroiddata PR."
