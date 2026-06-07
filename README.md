# Wristotle Companion

The phone half of Wristotle — a voice-driven Pebble watch companion
that runs on-device by default.

→ **Docs:** <https://wristotle.codeberg.page/>
→ **Watch app:** <https://codeberg.org/wristotle/wristotle>

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
