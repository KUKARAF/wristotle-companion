# Wristotle Companion

Android companion app for the [Wristotle](../Wristotle) Pebble watch app. Bridges your watch to phone capabilities — calls, SMS, contacts, reminders — and ships a system-wide, on-device speech recognition provider you can plug into.

**User docs:** <https://wristotle.codeberg.page/> — install guide, voice commands, troubleshooting, privacy details. The sections below are the developer reference.

**Support the project:** <https://wristotle.codeberg.page/support/> — buy a coffee, or just star the [companion](https://codeberg.org/wristotle/wristotle-companion) and [watch](https://codeberg.org/wristotle/wristotle) repos. Both help.

---

**Contents**

- [For users](#for-users)
  - [What it does](#what-it-does)
  - [What you need](#what-you-need)
  - [Install](#install)
  - [First-time setup](#first-time-setup)
  - [Use Wristotle as system-wide voice input *(optional)*](#use-wristotle-as-system-wide-voice-input-optional)
- [For developers](#for-developers)
  - [Prerequisites](#prerequisites)
  - [Clone and build](#clone-and-build)
  - [Modules](#modules)
  - [Project structure](#project-structure)
  - [Adding a new command handler](#adding-a-new-command-handler)
  - [Permissions](#permissions)
  - [Watch dictation quirk](#watch-dictation-quirk)

---

## For users

### What it does

Dictate from your watch; the command runs on your phone. Supported phrases:

| Say on the watch                 | What happens on the phone        |
|----------------------------------|----------------------------------|
| "Call [name]" / "Dial [name]"    | Places a call to that contact    |
| "Text [name] [message]"          | Sends an SMS to that contact     |
| "WhatsApp [name] [message]" / "Telegram [name] [message]" / "Signal [name] [message]" | Opens a pre-filled draft in the target app (auto-send not yet supported; SMS is the only programmatically dispatched channel) |
| "Remind me to [thing] at [time]" | Creates a watch-side reminder    |
| "List reminders" / "What are my reminders" | Reads back the pending pins |
| "Cancel [reminder]" / "Reschedule [reminder] to [time]" | Targets by descriptor; bare "cancel" still targets the latest |
| "Open [app]" / "Launch [app]"    | Launches the named app           |
| "Play [app]" / "Pause [app]"     | Plays / pauses media in that app |
| "Play" / "Pause" / "Next" / "Previous" | Acts on the currently playing app |
| "Rewind 10 seconds" / "Skip ahead 30 seconds" | Seek within current track |
| "When is my next meeting" / "What's on my calendar [day]" | Reads your phone calendar back |
| "Schedule a meeting [day] at [time]" | Creates an event on your phone calendar |
| "Note to self: [body]" / "Take a note: [body]" | Saves a voice note (browsable on watch + companion Notes tab) |
| "Append [body]" / "Add to my last note: [body]" | Appends to the most recent note |
| "Add task [body]" / "New task [body]" | Adds a to-do (browsable on watch + companion Tasks tab) |
| "What are my tasks" / "What are my completed tasks" | Lists pending or completed tasks |
| "Complete [task]" / "Delete task [task]" / "Mark [task] done" | Substring-matches against pending tasks (or "complete the last task") |

Calendar phrases ("when is my next meeting", "schedule a meeting tomorrow at
3pm") are natural-language rather than verb-first, so they route through the
**Intent Model** (below) rather than the built-in prefix table. They read and
write the **phone** calendar via `CalendarContract` (Calendar permission), not
the watch timeline.

Wristotle Companion ships an `android.speech.RecognitionService` backed by on-device Whisper, and can register itself as Android's default speech recognition service. In principle any app that calls `android.speech.SpeechRecognizer` would then transcribe through Wristotle. We haven't verified which third-party apps actually exercise that API in practice — many bundle their own engine — so treat the system-wide voice input feature as experimental.

The canonical phrasings above (verb-first: `call`, `text`, `remind`, `cancel`, `play`, `pause`, `next`, `previous`, `open`, `launch`) work on a fresh install with no extra downloads — they route through a built-in prefix table. The optional **Intent Model** (a small on-device sentence encoder, ~23 MB) is a *polish layer* that adds tolerance for natural paraphrases ("ring Mom" instead of "call Mom", "tell Dad I'm running late" instead of "text Dad …", "buzz me at 3" instead of "remind me at 3"). It doesn't unlock new actions — only new ways to phrase the same ones.

App-name commands (`open …`, `play …`, `pause …`) need a one-time **Scan installed apps** tap in *Settings → Learning → Installed apps* so the companion knows what's on the device; re-scan after installing or uninstalling apps. Cross-app media control also needs **Notification Access** — Wristotle requests it as part of the first-run permission flow, but you can grant or revoke it later via the *Media Control* card on the Permissions tab.

Every interaction — calls, texts, reminders, media commands, locally-handled commands like "what time is it" — is saved to a local **Conversation** history on the phone. The companion has five tabs: **Conversation** (the app's landing screen — long-press any message bubble to copy text), **Notes**, **Tasks**, **Permissions**, and **Settings**. The Settings tab is a drill-down list of eight categories — **Watch** (mirror of the watch app's own settings: vibration, dictation confirmation, routing, quick-launch, confirm-before-send + timeout + default action, logging — editable from the phone and saved back to the watch; keep the watch app open while loading or saving), **Conversation** (history retention 1 / 10 / 20 / 30 days, default 10 + optional audio capture of the last 5 dictations, off by default for privacy), **Notes** (keep-last-N + append-audio mode), **Reminders** (default offset when no time is spoken), **Models** (Speech / Intent downloads), **Learning** (installed-app index, app aliases, contact aliases, intent learning), **Backup & Restore** (export your notes / tasks / conversations / learned phrases / app + contact aliases / reminders / settings as a ZIP via the system Save-As dialog — optional AES-256 password, optional audio inclusion — and re-import with a merge-by-ID flow that keeps existing local data), and **Diagnostics** (bug-report export). Each card title shows an "ⓘ" icon — tap it to expand a detailed description in place. The fourth tab, **Permissions**, consolidates the watch-bridge perms (Contacts / Phone / SMS), the voice perms (Record Audio, battery exemption, default-voice-provider activation), and Notification Access for media control.

### What you need

- An Android phone running Android 7.0 (API 24) or newer
- A Pebble watch with the [Wristotle](../Wristotle) watch app installed
- A Pebble Android companion app already running on the phone (e.g. [microPebble](https://github.com/matejdro/micropebble) or [rePebble](https://rebble.io)) — this is what actually talks Bluetooth to the watch; Wristotle Companion plugs into it

### Install

Grab the latest APK from [Releases](https://codeberg.org/wristotle/wristotle-companion/releases) and sideload it. New releases are built and signed automatically on every `vX.Y.Z` tag push, so the topmost release is the current main branch.

Or build from source — see [For developers](#for-developers).

### First-time setup

1. **Open Wristotle Companion.**
2. **Grant permissions.** Tap *Grant Permissions* and walk through the chain — runtime perms (Contacts / Phone / SMS / Notifications) → battery-optimisation exemption → Notification Access (the last one is what unlocks cross-app media control).
3. **Download a Whisper model.** Open *Settings → Models → Speech* and tap *Download* on a model. `Tiny (English, quantized)` (~32 MB) is the recommended starting point — fastest, smallest, accurate enough for short watch commands. The first model you download is set active automatically.
4. **(Optional) Scan installed apps.** Open *Settings → Learning → Installed apps* and tap *Scan installed apps* if you want `open <app>` / `play <app>` style commands to work. Re-scan after installing or uninstalling apps.
5. **Pair the watch.** Make sure your watch is paired and the [Wristotle](../Wristotle) watch app is installed.
6. **Try it.** Open Wristotle on the watch, press *Select*, and dictate one of the commands from the table above.

While the bridge is active the phone displays a persistent low-priority notification — that's the foreground service keeping the connection alive.

### Use Wristotle as system-wide voice input *(optional, experimental)*

Setting Wristotle as the device's default `voice_recognition_service` makes Android route any `SpeechRecognizer`-based call through Wristotle's on-device Whisper. We haven't verified which third-party apps actually use that API in practice (many bundle their own engine), so don't expect this to globally replace your keyboard's voice button — depends entirely on whether the keyboard goes through Android's standard speech API.

The *Voice Input (Whisper)* card in the app offers two activation methods via a dropdown:

- **System Settings** — works on a few ROMs (older LineageOS, certain forks). On stock Pixel / Samsung / current GrapheneOS the underlying intent re-routes to the Digital Assistant picker, which is a different setting that does *not* list Wristotle.
- **ADB command** — the universal path. Works on every Android device. Requires a one-time ADB setup; walkthrough below.

#### ADB activation walkthrough

The activation requires `WRITE_SECURE_SETTINGS`, a signature-level permission only granted to the `shell` user — so the command must run from a computer with ADB.

##### 1. Install ADB on your computer

| Platform | Command                                                                                  |
|----------|------------------------------------------------------------------------------------------|
| macOS    | `brew install --cask android-platform-tools`                                             |
| Linux    | `sudo apt install android-tools-adb` *(Debian/Ubuntu)*, or your distro's package         |
| Windows  | Download [Android SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools), unzip, add the folder to `PATH` |

Verify with `adb version` — it should print a version string.

##### 2. Enable Developer Options on the phone

*Settings → About phone → Build number* — tap **7 times**. You'll see *"You are now a developer"*.

##### 3. Enable USB debugging

*Settings → System → Developer options → USB debugging* — toggle on.

##### 4. Connect the phone to the computer via USB

The phone will prompt **"Allow USB debugging?"** with the computer's RSA fingerprint — tap **Allow**. Optionally tick *Always allow from this computer*.

##### 5. Confirm the connection

```bash
adb devices
```

You should see your device listed with status `device` (not `unauthorized`). If `unauthorized`, unplug and replug — the dialog will appear again.

##### 6. Run the activation command

Tap *Copy ADB activation command* in Wristotle's *Voice Input* card, then paste into a terminal:

```bash
adb shell settings put secure voice_recognition_service \
    com.lazydevs.wristotle/com.lazydevs.wristotle.speech.service.WhisperRecognitionService
```

No output on success. Re-open Wristotle Companion — the *Voice Input* card should now read *"Active — Wristotle is the system voice input provider"*.

##### 7. Verify *(optional)*

```bash
adb shell settings get secure voice_recognition_service
```

Should print `com.lazydevs.wristotle/com.lazydevs.wristotle.speech.service.WhisperRecognitionService`.

> **One-time setup.** The setting persists across phone restarts and Wristotle Companion updates — you only need to repeat this if you intentionally change the voice provider, uninstall the app, or factory-reset the device.

#### Wireless ADB *(Android 11+)*

If you'd rather not keep a USB cable around, Android 11+ supports wireless debugging:

1. On the phone: *Settings → System → Developer options → Wireless debugging* → **Pair device with pairing code**. Note the IP:port and 6-digit code.
2. On the computer: `adb pair <ip>:<port>` — enter the 6-digit code when prompted.
3. Then: `adb connect <ip>:<port>` *(use the port shown on the Wireless debugging screen itself, not the pairing port).*
4. Run the activation command exactly as in step 6 above.

Pairing persists; subsequent sessions only need step 3.

#### Reverting

Switch back to the stock Google service:

```bash
adb shell settings put secure voice_recognition_service \
    com.google.android.tts/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService
```

Or clear the setting entirely (Android falls back to whatever it considers default):

```bash
adb shell settings delete secure voice_recognition_service
```

---

## For developers

### Prerequisites

- **Android Studio** Iguana or later (Compose BOM 2026.02 baseline)
- **Android SDK** with platform 36
- **NDK** ≥ 30.0.14904198 — install via *Tools → SDK Manager → SDK Tools → NDK (Side by Side)*
- **CMake** — same dialog, separate install (NDK alone is not enough)
- **JDK 11+**

### Clone and build

Clone with submodules — `:speech-whisper` depends on [whisper.cpp](https://github.com/ggerganov/whisper.cpp) at a pinned tag:

```bash
git clone --recursive https://codeberg.org/wristotle/wristotle-companion.git
cd wristotle-companion
./gradlew :app:assembleDebug
```

If you cloned without `--recursive`, run `git submodule update --init` once.

The first `:speech-whisper:assembleDebug` compiles whisper.cpp + ggml from source (~5–10 min on first run; cached afterwards). The produced native libs total ~22 MB stripped — `libwhisper.so` (6 MB) + `libggml*.so` (~6.4 MB) + `libomp.so` + `libc++_shared.so` + the 197 KB `libwristotle_speech.so` JNI bridge.

Build outputs land in `app/build/outputs/apk/debug/`.

Install on a connected device:

```bash
./gradlew :app:installDebug
```

Build just the speech-recognition library:

```bash
./gradlew :speech-whisper:assembleDebug
```

### Git hooks

This repo ships a shared pre-push hook under `tools/git-hooks/`. It rejects tag pushes that don't match the current version series (defends against the "tagged the wrong repo" mistake) and warns when a release tag has no matching entry in [`HelpContent.kt`](app/src/main/java/com/lazydevs/wristotle/help/HelpContent.kt) — so user-facing releases don't ship with a stale in-app Help page.

Wire it into your clone once:

```bash
git config core.hooksPath tools/git-hooks
```

After that, edits to `tools/git-hooks/pre-push` take effect on the next push — no need to copy into `.git/hooks/`. To bypass for a one-off (emergency hotfix outside the current series): `git push --no-verify origin <tag>`.

### Modules

| Module            | Role                                                                                   |
|-------------------|----------------------------------------------------------------------------------------|
| `:app`            | The companion app — services, handlers (incl. cross-app media + open-app), slot extractors, AppIndex, ActiveMediaSession, UI, `WristotleApplication` |
| `:speech`         | System-wide `android.speech.RecognitionService` + audio sources + Recognizer interface + shared model plumbing (`ResumableDownloader`, `ModelFileStorage`) |
| `:speech-whisper` | whisper.cpp JNI backend, model catalog, `WhisperRecognizer`; storage is a thin `ModelFileStorage` subclass |
| `:speech-nlu`     | Intent classifier interface + ONNX-MiniLM embedding implementation + learnable example bank; storage is a thin `ModelFileStorage` subclass |

Both the recognition backend and the intent classifier are swappable via
single-line provider hooks in `WristotleApplication.onCreate`:

```kotlin
Recognizers.provider       = { ctx -> WhisperRecognizer(ctx, modelPath = ...) }
IntentClassifiers.provider = { ctx -> EmbeddingIntentClassifier(...) }
```

Neither the `:speech` module nor `WhisperRecognitionService` references a
concrete recognizer; nothing outside `:app` and `:speech-nlu` references
a concrete classifier.

### Project structure

```
app/src/main/java/com/lazydevs/wristotle/
  AppConstants.kt               # PEBBLE_UUID, notification constants
  MainActivity.kt               # Starts foreground service; chains runtime perms →
                                #   battery exemption → notification listener page
  WristotleApplication.kt       # Owns shared PebbleTransport, recognizer + classifier
                                #   providers, slot extractor registry, AppIndex,
                                #   ActiveMediaSession, conversation audio store.
                                #   Skips NLU on isLowRamDevice().
  handlers/
    ActionHandler.kt            # Interface: tag + intent + handle(IntentResult)
    HandlerRegistry.kt          # 1:1 intent → handler map; returns (response, handler, success)
    CallHandler.kt, SmsHandler.kt, ReminderHandler.kt,
    CancelReminderHandler.kt, FindPhoneHandler.kt
    OpenAppHandler.kt           # "open <app>" — pure launch via AppIndex
    MediaHandlers.kt            # MediaPlay/Pause/PlayPause/Next/Previous/Seek handlers
    AppTarget.kt                # Sealed type + IntentResult.resolveAppTarget extension
                                #   (collapses AppLookup + "no slot" → Specific/Fallback/NotFound)
    TimeParser.kt               # prettytime-nlp + word-number normalisation (called from ReminderSlots)
    PinStore.kt                 # SharedPreferences ring buffer of recent pin IDs
  nlu/
    NluSettings.kt              # Learning toggle + ROUTE_THRESHOLD/ROUTE_MARGIN constants
    LearningCollector.kt        # Dedupe-insert + debounced classifier rebuild on success
    PrefixHints.kt              # Opening-verb tie-breaker (call/text/play/pause/open/…)
    slots/
      CallSlots.kt, SmsSlots.kt, ReminderSlots.kt,
      CancelSlots.kt, FindPhoneSlots.kt,
      MediaPlaySlots.kt         # {app} from "play <X>"; empty for "play"
      MediaTargetSlots.kt       # {app} for pause/next/previous body
      MediaSeekSlots.kt         # {seconds} digit + word-form number parser
      OpenAppSlots.kt           # {app} for "open/launch/fire up/switch to <X>"
      SlotUtils.kt              # stripVerbBody, stripTrailingEmphasis, cleanNameToken
  apps/
    InstalledApp.kt, InstalledAppDao.kt,
    AppIndexDatabase.kt         # Separate Room DB (wristotle-app-index.db, v2)
    AppIndexer.kt               # Scans launcher apps on user trigger
    AppIndex.kt                 # 3-way AppLookup (Match/Generic/NotFound),
                                #   4-tier match: exact → prefix → contains → reverse-contains,
                                #   each tier label-first then package-fallback
    AppLabel.kt                 # normalizeForIndex (labels) + normalizeForPackageId
                                #   (strip TLD/middle/suffix segments from package id)
    AppLauncher.kt              # launchApp + packageLabel shared helpers
  media/
    ActiveMediaSession.kt       # MediaSessionManager facade: active-session ops +
                                #   per-package targeted ops (3-tier: controller →
                                #   targeted MEDIA_BUTTON broadcast → system key event) +
                                #   pauseOthers() to prevent dual playback on launch
    MediaSessionsListener.kt    # Empty NotificationListenerService — presence + Notification
                                #   Access grant unlocks MediaSessionManager.getActiveSessions
  history/
    ConversationEntry.kt        # Room @Entity (audio + NLU fields included)
    ConversationDao.kt          # insert / observe-newest-first / prune / count-older-than / delete-all
    ConversationDatabase.kt     # Room @Database, single-baseline schema
    ConversationRepository.kt   # Retention wrapper; wipes audio dir on clearAll
    ConversationSettings.kt     # SharedPreferences retention window (1/10/20/30 days, default 10)
    ConversationAudioSettings.kt # SharedPreferences capture toggle (default off, privacy)
    ConversationAudioStore.kt   # filesDir/conversation-audio/, FIFO at MAX_FILES=5
  phone/
    ContactsRepository.kt       # Contact lookup on Dispatchers.IO
  util/
    Permissions.kt              # Context.hasPermission extension shared by handlers + VMs
  service/
    WatchMessageService.kt      # Foreground LifecycleService — keep-alive + COMPANION_READY
    PebbleListenerService.kt    # Bound by Pebble companion; resolveIntent + dispatch + log;
                                #   onBind captures the binder's UID for companion-detection
  transport/
    MessageKeys.kt              # AppMessage key indices (sync with watch package.json)
    PebbleTransport.kt          # PebbleKit2 DefaultPebbleSender wrapper + NACK retry
    PebbleCompanionDetector.kt  # Classifies the active BLE companion (microPebble vs
                                #   Core Devices / legacy rePebble) — drives the dismissable
                                #   "Wristotle's Whisper isn't used for watch dictation under
                                #   Core Devices" banners across the UI. Three independent
                                #   dismiss flags + a sticky "show models anyway" flag.
  ui/
    MainScreen.kt               # Bottom-nav shell with three tabs (Chat / Permissions / Settings)
    MainViewModel.kt            # Permission state (incl. Notification Access) + default-voice-provider
    ConversationScreen.kt       # Default tab — chat-style history, inline play button per row
    ConversationViewModel.kt    # Wraps repository + audio settings + retention setter
    PermissionsScreen.kt        # Tab 2 — Watch Bridge / Voice Input / Media Control cards
    SettingsScreen.kt           # Tab 3 — Models / Learning / Conversation sections (nested)
    CardTitleWithInfo.kt        # Title row with collapsible "ⓘ" description
    ModelCardCommon.kt          # Shared ActiveModelPill + approxSizeMb across model cards
    ModelsViewModel.kt          # Generic abstract base for Whisper + NLU model picker VMs
    WhisperModelsCard.kt, WhisperModelsViewModel.kt
    NluModelsCard.kt, NluModelsViewModel.kt
    AppIndexCard.kt, AppIndexViewModel.kt

speech/src/main/java/com/lazydevs/wristotle/speech/
  Recognizers.kt                # Service-locator: @Volatile var provider
  audio/                        # AudioSource interface + MicAudioSource + PipeAudioSource
  recognizer/                   # Recognizer interface + TranscriptionEvent + StubRecognizer
  service/
    WhisperRecognitionService.kt # extends android.speech.RecognitionService
  model/                        # Shared by :speech-whisper + :speech-nlu
    DownloadStreamEvent.kt      # sealed Progress / Complete / Failed
    ResumableDownloader.kt      # HTTP + Range, manual redirects, throttled progress
    ModelFileStorage.kt         # Open base — per-family subclasses pass dir/prefs/filename

speech-whisper/
  build.gradle.kts              # NDK + CMake, arm64-v8a only, NDK 30.0 pinned
  src/main/cpp/
    CMakeLists.txt              # add_subdirectory(whisper.cpp) + JNI lib
    wristotle_speech.cpp        # JNI bridge + per-call timing logs + silence trim
    whisper.cpp/                # git submodule
  src/main/java/com/lazydevs/wristotle/speech/whisper/
    WhisperNative.kt            # JNI external fun decls + defaultThreadCount()
    WhisperRecognizer.kt        # Recognizer impl — buffers to EOF, runs whisper_full
    TranscriptDedup.kt          # Collapses repeated phrases from Whisper hallucinations
    ModelCatalog.kt             # Known Whisper models + HuggingFace URLs (incl. q5_1 quants)
    ModelStorage.kt             # 10-line ModelFileStorage subclass — whisper-models/, ggml-*.bin

speech-nlu/
  src/main/java/com/lazydevs/wristotle/speech/nlu/
    Intent.kt, IntentResult.kt                     # enum includes Media{Play,Pause,…} + OpenApp
    IntentClassifier.kt, IntentClassifiers.kt      # Interface + service-locator
    StubIntentClassifier.kt                        # Always-Unknown fallback
    slot/SlotExtractor.kt, SlotExtractorRegistry.kt
    embedding/MiniLmEmbedder.kt, Tokenizer.kt,
              CosineSimilarity.kt, EmbeddingIntentClassifier.kt
    bank/ExampleEntry.kt, ExampleDao.kt,
         NluDatabase.kt, ExampleBank.kt
    seed/SeedExamples.kt                           # Bundled phrasings per intent
    model/NluModelCatalog.kt
          NluModelStorage.kt                       # 10-line ModelFileStorage subclass
  src/main/res/raw/minilm_vocab                    # WordPiece vocab for the embedder
```

### Adding a new command handler

Routing is now intent-based — the NLU classifier picks the intent, the
`HandlerRegistry` maps it to a handler. Adding a brand-new capability
(e.g. Weather) takes six steps:

1. Add `Weather` to the `Intent` enum in `:speech-nlu`.
2. Add ~15 seed phrasings in `SeedExamples.kt` so the classifier can
   route to it.
3. (Optional) Implement `SlotExtractor` in `app/.../nlu/slots/WeatherSlots.kt`
   to pull structured fields from the query, and register it in
   `WristotleApplication`'s `SlotExtractorRegistry` map.
4. Implement `ActionHandler` in `handlers/`:

   ```kotlin
   class WeatherHandler(...) : ActionHandler {
       override val tag = "weather"          // shown as the chip in Conversation history
       override val intent = Intent.Weather  // 1:1 with HandlerRegistry's intent map
       override suspend fun handle(result: IntentResult): String =
           "Sunny and 72°F in ${result.slots["location"] ?: "your area"}"
   }
   ```

5. Register the handler in `PebbleListenerService.onCreate()`:

   ```kotlin
   registry = HandlerRegistry(listOf(
       CallHandler(this, contacts),
       SmsHandler(this, contacts),
       WeatherHandler(this),
   ))
   ```

6. (Optional) Add a `PrefixHints` rule so an unambiguous opening verb
   (`weather`) wins ambiguous classifier calls.

Watch app needs no changes — natural-language queries arrive over
`COMPANION_QUERY` and the classifier picks the right intent.

### Permissions

| Permission                     | Purpose                                                |
|--------------------------------|--------------------------------------------------------|
| `READ_CONTACTS`                | Contact lookup by name                                 |
| `CALL_PHONE`                   | Place calls                                            |
| `SEND_SMS`                     | Send text messages                                     |
| `RECORD_AUDIO`                 | Recognition service capture (mic mode + pipe fallback) |
| `INTERNET`                     | Downloading Whisper / MiniLM model files from HuggingFace |
| `POST_NOTIFICATIONS`           | Foreground service notification (Android 13+)          |
| `FOREGROUND_SERVICE`           | Long-running watch bridge                              |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | FGS type for the wearable bridge (Android 14+)         |
| `QUERY_ALL_PACKAGES`           | AppIndex scan — `open <app>` / `play <app>` resolve to a launcher package |
| `BIND_NOTIFICATION_LISTENER_SERVICE` *(via system Settings, not runtime)* | Cross-app media control — unlocks `MediaSessionManager.getActiveSessions` |

> **GrapheneOS / privacy-ROM note:** `INTERNET` is auto-granted on stock Android but may be denied by default on GrapheneOS and similar ROMs that surface it as a runtime permission. If model downloads fail with *"unable to resolve host"*, grant it via *Settings → Apps → Wristotle Companion → Permissions → Network*, or from ADB:
>
> ```bash
> adb shell pm grant com.lazydevs.wristotle android.permission.INTERNET
> ```

### Watch dictation quirk

`WhisperRecognitionService` must drain the `RecognizerIntent.EXTRA_AUDIO_SOURCE` pipe to EOF *before* calling `callback.results(...)`. Returning early — even with a valid transcript — produces a malformed `DictationResult` packet on the watch firmware's side and surfaces as a generic *"Could not understand. Try again."* The `Recognizer` interface bakes this contract in: `TranscriptionEvent.Final` is only emitted after `AudioSource.samples()` completes.

---

## License

MIT — see [LICENSE](LICENSE). The bundled `whisper.cpp` submodule is also MIT (Georgi Gerganov); ONNX Runtime is MIT (Microsoft); MiniLM-L6-v2 is Apache 2.0 (Microsoft Research). Per-dependency licenses ship with each library's metadata.
