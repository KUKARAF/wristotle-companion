#!/usr/bin/env python3
"""regenerate_help_timeline.py — generate HelpTimeline.kt from features.json.

Canonical source: wristotle-docs/data/features.json (synced into this repo
via tools/sync_features.sh). Codegen target:
    app/src/main/java/com/lazydevs/wristotle/help/HelpTimeline.kt

The hand-edited HelpContent.kt holds tips + data class definitions + the
HelpContent object surface; this generated file holds only the timeline
list so a release update is one JSON edit + one script run.

Run from the companion repo root. No external dependencies — uses
stdlib only.
"""
from __future__ import annotations

import json
import pathlib
import sys
import textwrap

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
FEATURES_JSON = REPO_ROOT / "data" / "features.json"
TIMELINE_KT = REPO_ROOT / "app" / "src" / "main" / "java" / "com" / "lazydevs" / "wristotle" / "help" / "HelpTimeline.kt"


def kotlin_string(s: str | None) -> str:
    """Render a Python string as a Kotlin literal. None → `null`."""
    if s is None:
        return "null"
    escaped = (
        s.replace("\\", "\\\\")
         .replace("\"", "\\\"")
         .replace("$", "\\$")
         .replace("\n", "\\n")
    )
    return f'"{escaped}"'


def emit_entry(feature: dict) -> str:
    """Render one FeatureEntry literal, indented for placement inside listOf(...)."""
    lines = [
        f"        FeatureEntry(",
        f"            id = {kotlin_string(feature['id'])},",
        f"            date = {kotlin_string(feature['date'])},",
        f"            companionVersion = {kotlin_string(feature.get('companion_version'))},",
        f"            watchVersion = {kotlin_string(feature.get('watch_version'))},",
        f"            title = {kotlin_string(feature['title'])},",
        f"            description = {kotlin_string(feature['description'])},",
        f"            sampleQuery = {kotlin_string(feature.get('sample_query'))},",
        f"            docsPath = {kotlin_string(feature.get('docs_path'))},",
        f"        ),",
    ]
    return "\n".join(lines)


def validate(features: list[dict]) -> None:
    """Light structural sanity checks — fail loudly on the kind of authoring
    mistake that would otherwise compile and surface as silent UI breakage."""
    seen_ids: set[str] = set()
    required = {"id", "date", "title", "description"}
    for i, feature in enumerate(features):
        missing = required - feature.keys()
        if missing:
            raise SystemExit(f"feature #{i} missing required keys: {sorted(missing)}")
        if feature["id"] in seen_ids:
            raise SystemExit(f"duplicate feature id: {feature['id']}")
        seen_ids.add(feature["id"])
        if feature.get("companion_version") is None and feature.get("watch_version") is None:
            raise SystemExit(
                f"feature '{feature['id']}' must have at least one of "
                "companion_version / watch_version"
            )


def render() -> tuple[str, int]:
    """Build the HelpTimeline.kt contents from features.json. Returns
    (kotlin_source, feature_count). Raises SystemExit on validation errors."""
    if not FEATURES_JSON.exists():
        raise SystemExit(f"✗ {FEATURES_JSON} not found. Run tools/sync_features.sh first.")

    payload = json.loads(FEATURES_JSON.read_text())
    features = payload["features"]
    validate(features)

    entries = "\n".join(emit_entry(f) for f in features)
    header = textwrap.dedent('''\
        package com.lazydevs.wristotle.help

        /**
         * GENERATED FILE — DO NOT EDIT BY HAND.
         *
         * Regenerate via:
         *   tools/sync_features.sh                  # pull data/features.json from docs
         *   python3 tools/regenerate_help_timeline.py
         *
         * Canonical source: wristotle-docs/data/features.json.
         * Style guide for new entries: HelpContent.kt's file-level KDoc.
         */
        internal object HelpTimeline {
            val timeline: List<FeatureEntry> = listOf(
        ''')
    footer = "    )\n}\n"
    return header + entries + "\n" + footer, len(features)


def main(argv: list[str]) -> int:
    check_mode = "--check" in argv
    contents, count = render()

    if check_mode:
        # Exit 1 (not 0) when regenerating would change the file.
        # Used by the pre-push hook to refuse pushes with a stale
        # HelpTimeline.kt vs the current features.json.
        if not TIMELINE_KT.exists():
            print(f"✗ {TIMELINE_KT.relative_to(REPO_ROOT)} missing — run without --check first", file=sys.stderr)
            return 1
        if TIMELINE_KT.read_text() != contents:
            print(
                f"✗ {TIMELINE_KT.relative_to(REPO_ROOT)} is out of sync with "
                f"{FEATURES_JSON.relative_to(REPO_ROOT)} — "
                "run tools/regenerate_help_timeline.py and commit",
                file=sys.stderr,
            )
            return 1
        return 0

    TIMELINE_KT.parent.mkdir(parents=True, exist_ok=True)
    TIMELINE_KT.write_text(contents)
    print(f"✓ wrote {TIMELINE_KT.relative_to(REPO_ROOT)} ({count} features)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
