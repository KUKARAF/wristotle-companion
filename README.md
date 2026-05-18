# Wristotle Companion

Android companion app for the [Wristotle](../Wristotle) Pebble watch app. Bridges your watch to phone capabilities — calls, SMS, contacts, reminders — and ships a system-wide, on-device speech recognition provider you can plug into.

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
| "Remind me to [thing] at [time]" | Creates a watch-side reminder    |
| "Cancel reminder"                | Cancels the most recent reminder |

Beyond watch dictation, Wristotle Companion can also register as Android's *system-wide* voice input provider, so any app on the device — keyboards, search bars, third-party apps — transcribes through the same on-device Whisper engine.

Phrasings beyond the canonical verbs work too — once you download the optional **Intent Model** (a small on-device sentence encoder), Wristotle understands natural variants like "ring Mom", "tell Dad I'm running late", or "buzz me at 3" by routing them to the right intent. Without the model, the existing prefix matching is used and the canonical phrasings above still work.

Every interaction — calls, texts, reminders, locally-handled commands like "what time is it" — is saved to a local **Conversation** history on the phone. The Conversation tab is the app's landing screen; long-press any message bubble to copy text. The **Settings** tab lets you choose how long to keep history (1 / 10 / 20 / 30 days, default 30), wipe it on demand, and optionally **save the audio** of your last few dictations (capped at 5, off by default) so you can replay any command from the chat. The third tab, **Permissions**, consolidates the watch-bridge perms (Contacts / Phone / SMS) and the voice perms (Record Audio, battery exemption, default-voice-provider activation) in one place.

### What you need

- An Android phone running Android 7.0 (API 24) or newer
- A Pebble watch with the [Wristotle](../Wristotle) watch app installed
- A Pebble Android companion app already running on the phone (e.g. [microPebble](https://github.com/matejdro/micropebble) or [rePebble](https://rebble.io)) — this is what actually talks Bluetooth to the watch; Wristotle Companion plugs into it

### Install

Grab the latest APK from [Releases](https://codeberg.org/kchinnasamy/wristotle-companion/releases) and sideload it. New releases are built and signed automatically on every `vX.Y.Z` tag push, so the topmost release is the current main branch.

Or build from source — see [For developers](#for-developers).

### First-time setup

1. **Open Wristotle Companion.**
2. **Grant permissions.** Tap *Grant Permissions* and accept Contacts, Phone, SMS, Microphone, and Notifications.
3. **Download a Whisper model.** Open the *Whisper Models* card and tap *Download* on a model. `Tiny (English, quantized)` (~32 MB) is the recommended starting point — fastest, smallest, accurate enough for short watch commands. The first model you download is set active automatically.
4. **Pair the watch.** Make sure your watch is paired and the [Wristotle](../Wristotle) watch app is installed.
5. **Try it.** Open Wristotle on the watch, press *Select*, and dictate one of the commands from the table above.

While the bridge is active the phone displays a persistent low-priority notification — that's the foreground service keeping the connection alive.

### Use Wristotle as system-wide voice input *(optional)*

Once enabled, anything on your phone that uses Android's `SpeechRecognizer` (keyboard mic buttons, voice search, etc.) will transcribe via Wristotle.

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
git clone --recursive https://codeberg.org/kchinnasamy/wristotle-companion.git
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

### Modules

| Module            | Role                                                                                   |
|-------------------|----------------------------------------------------------------------------------------|
| `:app`            | The companion app — services, handlers, slot extractors, UI, `WristotleApplication`    |
| `:speech`         | System-wide `android.speech.RecognitionService` + audio sources + Recognizer interface |
| `:speech-whisper` | whisper.cpp JNI backend, model catalog / storage / downloader, `WhisperRecognizer`     |
| `:speech-nlu`     | Intent classifier interface + ONNX-MiniLM embedding implementation + per-intent slot extractors + learnable example bank |

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
  MainActivity.kt               # Starts foreground service, requests permissions
  WristotleApplication.kt       # Owns shared PebbleTransport, recognizer + classifier
                                #   providers, slot extractor registry, conversation
                                #   audio store. Skips NLU on isLowRamDevice().
  handlers/
    ActionHandler.kt            # Interface: tag + intent + handle(IntentResult)
    HandlerRegistry.kt          # 1:1 intent → handler map; returns (response, handler, success)
    CallHandler.kt, SmsHandler.kt, ReminderHandler.kt,
    CancelReminderHandler.kt, FindPhoneHandler.kt
    TimeParser.kt               # prettytime-nlp + word-number normalisation (called from ReminderSlots)
    PinStore.kt                 # SharedPreferences ring buffer of recent pin IDs
  nlu/
    NluSettings.kt              # Learning toggle + ROUTE_THRESHOLD/ROUTE_MARGIN constants
    LearningCollector.kt        # Dedupe-insert + debounced classifier rebuild on success
    PrefixHints.kt              # Opening-verb tie-breaker for ambiguous classifier output
    slots/
      CallSlots.kt, SmsSlots.kt, ReminderSlots.kt,
      CancelSlots.kt, FindPhoneSlots.kt
      SlotUtils.kt              # stripTrailingEmphasis() + cleanNameToken() helpers
  history/
    ConversationEntry.kt        # Room @Entity (audio + NLU fields included)
    ConversationDao.kt          # insert / observe-newest-first / prune / count-older-than / delete-all
    ConversationDatabase.kt     # Room @Database with v1→v2→v3 migrations
    ConversationRepository.kt   # Retention wrapper; wipes audio dir on clearAll
    ConversationSettings.kt     # SharedPreferences-backed retention window (1/10/20/30 days)
    ConversationAudioSettings.kt # SharedPreferences-backed capture toggle (default off)
    ConversationAudioStore.kt   # filesDir/conversation-audio/, FIFO eviction at MAX_FILES=5
  phone/
    ContactsRepository.kt       # Contact lookup on Dispatchers.IO
  service/
    WatchMessageService.kt      # Foreground LifecycleService — keep-alive + COMPANION_READY
    PebbleListenerService.kt    # Bound by Pebble companion; resolveIntent + dispatch + log
  transport/
    MessageKeys.kt              # AppMessage key indices (sync with watch package.json)
    PebbleTransport.kt          # PebbleKit2 DefaultPebbleSender wrapper + NACK retry
  ui/
    MainScreen.kt               # Bottom-nav shell with three tabs (Chat / Permissions / Settings)
    MainViewModel.kt            # Permission state + default-voice-provider state
    ConversationScreen.kt       # Default tab — chat-style history, inline play button per row
    ConversationViewModel.kt    # Wraps repository + audio settings + retention setter
    PermissionsScreen.kt        # Tab 2 — Watch Bridge card + Voice Input card stacked
    SettingsScreen.kt           # Tab 3 — sectioned cards (Speech / Intent / Storage)
    WhisperModelsCard.kt        # One-line-per-model compact list, tap-to-activate
    WhisperModelsViewModel.kt   # Model catalog + download / activate state
    NluModelsCard.kt, NluModelsViewModel.kt        # Twin of Whisper card for the NLU model

speech/src/main/java/com/lazydevs/wristotle/speech/
  Recognizers.kt                # Service-locator: @Volatile var provider
  audio/                        # AudioSource interface + MicAudioSource + PipeAudioSource
  recognizer/                   # Recognizer interface + TranscriptionEvent + StubRecognizer
  service/
    WhisperRecognitionService.kt # extends android.speech.RecognitionService

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
    ModelStorage.kt             # filesDir/whisper-models/ + active model id
    ModelDownloader.kt          # Flow<DownloadEvent>, resumable HTTP, throttled progress

speech-nlu/
  src/main/java/com/lazydevs/wristotle/speech/nlu/
    Intent.kt, IntentResult.kt
    IntentClassifier.kt, IntentClassifiers.kt      # Interface + service-locator
    StubIntentClassifier.kt                        # Always-Unknown fallback
    slot/SlotExtractor.kt, SlotExtractorRegistry.kt
    embedding/MiniLmEmbedder.kt, Tokenizer.kt,
              CosineSimilarity.kt, EmbeddingIntentClassifier.kt
    bank/ExampleEntry.kt, ExampleDao.kt,
         NluDatabase.kt, ExampleBank.kt
    seed/SeedExamples.kt                           # Bundled phrasings per intent
    model/NluModelCatalog.kt, NluModelStorage.kt,
          NluModelDownloader.kt                    # Mirrors :speech-whisper's pattern
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
| `INTERNET`                     | Downloading Whisper model files from HuggingFace       |
| `POST_NOTIFICATIONS`           | Foreground service notification (Android 13+)          |
| `FOREGROUND_SERVICE`           | Long-running watch bridge                              |
| `FOREGROUND_SERVICE_DATA_SYNC` | FGS type required on Android 14+                       |

> **GrapheneOS / privacy-ROM note:** `INTERNET` is auto-granted on stock Android but may be denied by default on GrapheneOS and similar ROMs that surface it as a runtime permission. If model downloads fail with *"unable to resolve host"*, grant it via *Settings → Apps → Wristotle Companion → Permissions → Network*, or from ADB:
>
> ```bash
> adb shell pm grant com.lazydevs.wristotle android.permission.INTERNET
> ```

### Watch dictation quirk

`WhisperRecognitionService` must drain the `RecognizerIntent.EXTRA_AUDIO_SOURCE` pipe to EOF *before* calling `callback.results(...)`. Returning early — even with a valid transcript — produces a malformed `DictationResult` packet on the watch firmware's side and surfaces as a generic *"Could not understand. Try again."* The `Recognizer` interface bakes this contract in: `TranscriptionEvent.Final` is only emitted after `AudioSource.samples()` completes.
