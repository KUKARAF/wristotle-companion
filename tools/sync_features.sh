#!/bin/sh
# sync_features.sh — pull data/features.json from the wristotle-docs sibling
# checkout into this companion repo. The docs repo is the canonical source
# (option 3 in the help-page architecture conversation); this script just
# refreshes the companion-side snapshot that the codegen reads.
#
# Run from the companion repo root after editing wristotle-docs's
# data/features.json. Run tools/regenerate_help_timeline.py afterward to
# refresh HelpTimeline.kt.
#
# Assumes wristotle-docs is checked out at ../wristotle-docs (override with
# DOCS_REPO env var if it lives elsewhere).

set -e

DOCS_REPO=${DOCS_REPO:-../wristotle-docs}
SRC="$DOCS_REPO/data/features.json"
DST="data/features.json"

if [ ! -f "$SRC" ]; then
    echo "✗ sync_features: $SRC not found." >&2
    echo "  Set DOCS_REPO if wristotle-docs lives elsewhere." >&2
    exit 1
fi

cp "$SRC" "$DST"
echo "✓ synced $SRC → $DST"
echo "  Next: python3 tools/regenerate_help_timeline.py"
