<!-- SPDX-License-Identifier: AGPL-3.0-only -->
<!-- Copyright (C) 2026 Lazy Devs -->

# Feature / change checklist

A new feature or a major change has to be wired into **every** cross-cutting
system, or it silently half-works — e.g. a new intent that never appears in the
Speak picker, or whose data is silently left out of Backup. This file is the
guard against that.

**How to use it:** match your change to the *triggers* below and do every box in
each matching section. Most changes hit several sections. The first section
(**Every new intent**) is mandatory for any new voice intent; the rest are
conditional. When you're done, run the **Definition of done** at the bottom.

A few of these are auto-enforced (they fail the build): `HandlerRegistry` throws
on a duplicate/missing intent at startup, `verifyHelpTimeline` gates the build,
and the pre-push hook compiles commonMain for the iOS Sim. Everything else is on
you — that's why this list exists.

---

## Trigger: Every new voice intent  *(always required)*

The minimum to make an intent route, classify, and dispatch.

- [ ] **Intent enum** — add the case to
  `wristotle-core/.../speech/nlu/Intent.kt` (persisted by **name** — renames need
  a Room migration of the example bank; reordering is safe).
- [ ] **Slot extractor** — new `…/slots/XxxSlots.kt` (in `:wristotle-core` unless
  it needs an Android API), implementing `SlotExtractor`.
- [ ] **Register the slot extractor in BOTH places** (they must stay in sync):
  - prod: `app/.../WristotleApplication.kt` → `SlotExtractorRegistry(mapOf(…))`
  - test: `app/src/test/.../nlu/pipeline/TestSlotRegistry.kt`
- [ ] **Handler** — new `app/.../handlers/XxxHandler.kt` implementing
  `ActionHandler` (set a short, stable `tag` — it's the Stats/history key).
- [ ] **Register the handler** in `app/.../service/PebbleListenerService.kt` →
  `HandlerRegistry(listOf(…))`. (Startup throws if an intent has 0 or 2 handlers.)
- [ ] **PrefixHints rescue rule** —
  `wristotle-core/.../speech/nlu/PrefixHints.kt`. Mind ordering anti-rules; add a
  regression row to `app/src/test/.../nlu/PrefixHintsTest.kt`.
- [ ] **Seed phrasings** — `wristotle-core/.../speech/nlu/seed/SeedExamples.kt`
  (~15–25 varied examples; the classifier cold-starts on these).
- [ ] **Confirm gate** —
  `wristotle-core/.../speech/nlu/handler/IntentDestructiveness.kt`. If the intent
  mutates user data / has outward side effects, add it so it prompts. Read-only
  intents need no entry but **confirm you considered it.**
- [ ] **On-watch Speak picker** — the trap that bit Sports:
  - add `const val INTENT_XXX = "Xxx"` (value = `Intent.Xxx.name`) in
    `wristotle-core/.../speech/nlu/settings/TtsProviderSettings.kt`
  - add a row to `INTENT_GROUPS` in `app/.../ui/TtsProviderCard.kt`
  - if it should speak out of the box, add it to `DEFAULT_INTENTS`
- [ ] **Stats label** — add `tag → "Display Name"` to `HANDLER_NAMES` in
  `app/.../ui/StatsCard.kt` (else Stats shows the raw tag). Blocklist it there if
  it's not user-meaningful.

## Trigger: The feature stores its own data (Room entity, etc.)

Backup is bidirectional — miss a step and data silently doesn't travel.

- [ ] **`*Json` codec** — encoder (Row → JSON) + decoder (JSON → Row); encoder
  emits `CURRENT_SCHEMA`, decoder accepts `1..CURRENT_SCHEMA`.
- [ ] **`BackupSelection`** (`wristotle-core/.../speech/nlu/backup/BackupSelection.kt`)
  — add a field, then update **all** of: `allSelected` / `noneSelected` /
  `anySecretSelected` / the `and` operator / `ALL` / `NONE` / `LEGACY_FULL`.
  Content defaults `true`; secrets default `false`.
- [ ] **`BackupManifest`** — record presence of the new data in the ZIP.
- [ ] **`BackupExporter` + `BackupImporter`** (`app/.../backup/`) — gate write/read
  on the selection field; add `MergeStrategies` dedupe rule for restore collisions.
- [ ] **Round-trip test** for the codec + merge (pure JUnit, no Robolectric):
  export → wipe → import restores the new data intact.

## Trigger: The feature has user-facing settings

- [ ] **`XxxSettings`** SharedPrefs class in `:wristotle-core` via the
  `KeyValueStore` seam (Android adapter in `:app/storage`).
- [ ] Expose it from `WristotleApplication.kt` (lazy val).
- [ ] **Settings card** + a `SettingsCategory` entry in `app/.../ui/SettingsScreen.kt`
  (mark `experimental` if applicable).
- [ ] If any setting should survive backup, also do the **stores-data** section
  (settings block in exporter/importer; secrets gated separately, default off).

## Trigger: The feature talks to the watch (new wire messages)

- [ ] Add the key to the **watch** `package.json` `messageKeys` FIRST, then sync
  `wristotle-core/.../transport/MessageKeys.kt` (the two must match exactly).
- [ ] Bump the **watch** `package.json` version before tagging the watch (CI does
  not rewrite it from the tag).

## Trigger: The feature uses the network

- [ ] Go through the `HttpClient` seam (Android impl wraps `SimpleHttp`); use
  `kotlinx.serialization`, not `org.json`.
- [ ] **Never** log an API key / auth header. Redact to URL + status + body
  excerpt only.
- [ ] Consider a remote-config / bundled-fallback path for volatile data.

## Trigger: Diagnostics-worthy state

- [ ] Add non-secret feature state (enabled flags, error counts) to
  `app/.../diagnostics/DiagnosticsBuilder.kt`. **State only, never secrets.**

---

## Release checklist  *(every user-facing release)*

- [ ] Bump `versionCode` + `versionName` in `app/build.gradle.kts`
  (`major*10000 + minor*100 + patch`). **Feature/UI/wire/data → minor; fix/polish
  → patch.** Settle the number before tagging.
- [ ] **`data/features.json`** — one entry (a single ~10–20-word sentence, no
  internals jargon), then `python3 tools/regenerate_help_timeline.py` (never edit
  `HelpTimeline.kt` by hand). **Internals-only release → no entry, no row.**
- [ ] **fastlane changelog** — `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
- [ ] **Docs site** (`wristotle-docs`) — `docs/voice-commands.md` +
  `docs/changelog.md` if user-facing.
- [ ] Commit message: Conventional Commits `type(scope):`; **no `Co-Authored-By`
  trailer.** Grep the diff for real names / dictated phrases / numbers / keys.
- [ ] If a vendored library (e.g. `sportskapi` submodule) changed: commit + tag +
  **push it to its origin FIRST**, fetch+checkout that commit *inside the
  submodule* (the composite build compiles the submodule checkout, **not** the
  standalone clone), bump the pointer, then push the companion.

## Definition of done  *(run before tagging)*

```sh
./gradlew :wristotle-core:compileKotlinIosSimulatorArm64   # KMP iOS gate (pre-push runs this)
./gradlew :wristotle-core:testAndroidHostTest :app:testDebugUnitTest
./gradlew :app:verifyHelpTimeline :app:installDebug        # build + on-device smoke
```

- [ ] On-device smoke of the actual voice path on the watch (not just unit tests).
- [ ] Update any architecture/reference docs affected by the change.

---

### Highest-risk silent omissions (history)

| Forgotten | Symptom |
|---|---|
| `TtsProviderCard` / `INTENT_*` row | Intent can never be spoken — no picker entry (the Sports gap) |
| `BackupSelection` field / `*Json` codec | Feature data silently absent from backups |
| `TestSlotRegistry` (only updated prod) | Tests pass against a registry that doesn't match production |
| `HANDLER_NAMES` row | Stats shows the raw handler tag instead of a label |
| `IntentDestructiveness` | A mutating intent skips the confirm prompt |
| `SeedExamples` / `PrefixHints` | Intent classifies poorly or never routes |
| Real ESPN-shape test fixture | A parser bug hides behind invented JSON (the F1 `type.text` bug) |
