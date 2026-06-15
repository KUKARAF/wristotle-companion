# Wristotle Companion

<!-- Release -->
[![Release](https://img.shields.io/gitea/v/release/wristotle/wristotle-companion?gitea_url=https://codeberg.org&label=release&color=1793d1)](https://codeberg.org/wristotle/wristotle-companion/releases)
[![Build](https://codeberg.org/wristotle/wristotle-companion/actions/workflows/release.yml/badge.svg)](https://codeberg.org/wristotle/wristotle-companion/actions)
[![F-Droid](https://img.shields.io/badge/F--Droid-in%20review-yellow)](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/40246)
[![License](https://img.shields.io/badge/license-AGPL--3.0--only-blue)](LICENSE)

<!-- Docs -->
[![Docs](https://img.shields.io/badge/docs-online-success)](https://wristotle.codeberg.page/)

<!-- Watch app -->
[![Watch app — Rebble](https://img.shields.io/badge/watch%20app-Rebble-c2154f)](https://apps.rebble.io/en_US/application/6a0e71faced0bb000943bc90)
[![Watch app — rePebble](https://img.shields.io/badge/watch%20app-rePebble-f4511e)](https://apps.repebble.com/wristotle_6a0e71faced0bb000943bc90)

<!-- Donate -->
[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-FFDD00?logo=buymeacoffee&logoColor=black)](https://buymeacoffee.com/lazydevs)
[![Liberapay](https://img.shields.io/badge/Liberapay-donate-f6c915?logo=liberapay&logoColor=black)](https://liberapay.com/lazydevs/donate)

The phone half of Wristotle — a voice-driven Pebble watch companion
that runs on-device by default.

## What it does

- Voice routes from the Pebble dictation button to calls, texts,
  reminders, alarms, music, notes, tasks, weather, world clock,
  calculator, and a morning brief.
- On-device NLU + speech (Whisper) by default. Optional cloud
  Whisper, self-hosted STT, or BYO-key LLM (Anthropic /
  OpenAI-compat) — all off until you turn them on.
- No account, no tracking, no analytics. Baseline doesn't leave
  the phone.

## Install

- [Codeberg releases](https://codeberg.org/wristotle/wristotle-companion/releases) — signed APKs.
- F-Droid submission in progress.

[Full install guide](https://wristotle.codeberg.page/install/) on the docs site.

## Build from source

Requires JDK 17, Android SDK, NDK 26.

```bash
git clone https://codeberg.org/wristotle/wristotle-companion
cd wristotle-companion
./gradlew :app:assembleDebug
```

[Architecture overview + source layout](https://wristotle.codeberg.page/architecture/) on the docs site.

## License

AGPLv3 — see [LICENSE](LICENSE).

**License history.** Wristotle was MIT-licensed through June 7,
2026 (releases v1.0.0 – v1.5.0, and any clone of `main` from
before that date). From June 7, 2026 onward — and from v1.6.0 /
any clone of `main` after the relicense commit — Wristotle is
licensed under AGPLv3. The legal cutoff is the `LICENSE` file in
the commit you cloned: anyone holding an MIT-era checkout or
v1.5.0-or-earlier tag keeps MIT terms on that copy.

Bundled dependencies: `whisper.cpp` (MIT), ONNX Runtime (MIT),
MiniLM-L6-v2 (Apache 2.0) — all AGPLv3-compatible.
