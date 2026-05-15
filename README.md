# Wristotle Companion

Android companion app for the [Wristotle](../Wristotle) Pebble watch app. Bridges your watch to phone capabilities — calls, SMS, contacts, reminders — and ships a system-wide on-device speech recognition provider you can plug into.

---

## For users

### What it does

Voice commands you dictate on your watch route through this companion app and execute on your phone:

| Say on the watch                         | What happens on the phone        |
|------------------------------------------|----------------------------------|
| "Call [name]" / "Dial [name]"            | Places a call to that contact    |
| "Text [name] [message]"                  | Sends an SMS to that contact     |
| "Remind me to [thing] at [time]"         | Creates a watch-side reminder    |
| "Cancel reminder"                        | Cancels the most recent reminder |

Beyond watch dictation, Wristotle Companion can also be set as Android's *system* voice input provider, so any app on the device (keyboards, search bars, third-party apps) transcribes through the same on-device engine.

### What you need

- An Android phone running Android 7.0 (API 24) or newer
- A Pebble watch with the [Wristotle](../Wristotle) watch app installed
- A Pebble Android companion app already running on the phone (e.g. [microPebble](https://github.com/matejdro/micropebble) or [rePebble](https://rebble.io)) — this is what actually talks Bluetooth to the watch; Wristotle Companion plugs into it

### Install

Grab the latest APK from [Releases](https://codeberg.org/kchinnasamy/wristotle-companion/releases) and side-load it, or build from source (see *For developers* below).

### First-time setup

1. Open Wristotle Companion.
2. Tap **Grant Permissions** and accept Contacts, Phone, SMS, Microphone, and Notifications.
3. Make sure your watch is paired and the [Wristotle](../Wristotle) watch app is installed.
4. Open Wristotle on the watch, press Select, and dictate one of the commands above.

The phone displays a persistent low-priority notification while the bridge is active — that's the foreground service that keeps the connection alive.

### Optional: use Wristotle as system-wide voice input

Once enabled, anything on your phone that uses Android's `SpeechRecognizer` (keyboard mic buttons, voice search, etc.) will transcribe via Wristotle.

The Voice Input (Whisper) card in the app offers two paths:

**Open in Settings (works on a few ROMs only)** — opens Android's `ACTION_VOICE_INPUT_SETTINGS` intent. On older LineageOS and a few forks this lands you on the actual voice-input picker where Wristotle appears alongside other recognizers. **On most devices (stock Pixel, Samsung, current GrapheneOS) this re-routes to the Digital Assistant picker — a separate setting that does *not* list Wristotle.** If that happens, use the ADB path below.

**Copy ADB activation command (universal fallback)** — copies a one-liner to the clipboard. From a computer with this device connected via USB and ADB enabled, paste and run it. To revert later, run a similar command pointing the setting at your previous provider.

```bash
adb shell settings put secure voice_recognition_service \
    com.lazydevs.wristotle/com.lazydevs.wristotle.speech.service.WhisperRecognitionService
```

The activation requires the `WRITE_SECURE_SETTINGS` signature-level permission, which is why ADB is needed — there's no in-app shortcut on stock Android.

> **Note:** before transcription works you need to open the **Whisper Models** card in the app and download a model. `tiny.en` (75 MB) is the recommended starting point — fastest, English-only, fine for short watch commands. The first model you download is set active automatically.

---

## For developers

### Prerequisites

- **Android Studio** Iguana or later (Compose BOM 2026.02 baseline)
- **Android SDK** with platform 36
- **NDK** ≥ 30.0.14904198 — install via *Tools → SDK Manager → SDK Tools → NDK (Side by Side)*
- **CMake** — same dialog, separate install (NDK alone is not enough)
- **JDK 11+**

### Clone and build

Clone with submodules — `:speech-whisper` depends on the upstream
[whisper.cpp](https://github.com/ggerganov/whisper.cpp) repo at a pinned tag:

```bash
git clone --recursive https://codeberg.org/kchinnasamy/wristotle-companion.git
cd wristotle-companion
./gradlew :app:assembleDebug
```

If you cloned without `--recursive`, run `git submodule update --init` once.

The first `:speech-whisper:assembleDebug` compiles whisper.cpp + ggml from
source (~5-10 min on first run; cached afterwards). The produced native libs
total ~22 MB stripped — `libwhisper.so` (6 MB) + `libggml*.so` (~6.4 MB) +
`libomp.so` + `libc++_shared.so` + our 197 KB `libwristotle_speech.so`.

Build outputs: `app/build/outputs/apk/debug/`.

Install on a connected device:

```bash
./gradlew :app:installDebug
```

Build the speech-recognition library module on its own:

```bash
./gradlew :speech-whisper:assembleDebug
```

### Modules

| Module             | Role                                                                                  |
|--------------------|---------------------------------------------------------------------------------------|
| `:app`             | The companion app — services, handlers, UI, `WristotleApplication`                    |
| `:speech`          | System-wide `android.speech.RecognitionService` + audio sources + Recognizer interface |
| `:speech-whisper`  | whisper.cpp JNI backend, model catalog/storage/downloader, `WhisperRecognizer` |

The recognition backend is swappable via a single line in `WristotleApplication.onCreate`:

```kotlin
Recognizers.provider = { ctx -> WhisperRecognizer(ctx, modelPath = ...) }
```

The `:speech` module and `WhisperRecognitionService` never reference a concrete recognizer.

### Project structure

```
app/src/main/java/com/lazydevs/wristotle/
  AppConstants.kt               # PEBBLE_UUID, notification constants
  MainActivity.kt               # Starts foreground service, requests permissions
  WristotleApplication.kt       # Owns shared PebbleTransport + Recognizers provider hook
  handlers/
    ActionHandler.kt            # Interface: canHandle() + handle()
    HandlerRegistry.kt          # Dispatches to first matching handler
    CallHandler.kt              # "call/dial [name]"
    SmsHandler.kt               # "text/message [name] [body]"
    ReminderHandler.kt          # reminder_query → insertTimelinePin
    CancelReminderHandler.kt    # cancel_query → deleteTimelinePin via PinStore
    TimeParser.kt               # prettytime-nlp + word-number normalisation
    PinStore.kt                 # SharedPreferences ring buffer of recent pin IDs
  phone/
    ContactsRepository.kt       # Contact lookup on Dispatchers.IO
  service/
    WatchMessageService.kt      # Foreground LifecycleService — keep-alive + COMPANION_READY ping
    PebbleListenerService.kt    # Bound by the Pebble companion; dispatches to handlers
  transport/
    MessageKeys.kt              # AppMessage key indices (sync with watch package.json)
    PebbleTransport.kt          # PebbleKit2 DefaultPebbleSender wrapper
  ui/
    MainScreen.kt               # Permissions + voice input status (Compose Material3)
    MainViewModel.kt            # Permission state + default-voice-provider state

speech/src/main/java/com/lazydevs/wristotle/speech/
  Recognizers.kt                # Service-locator: @Volatile var provider
  audio/
    AudioSource.kt              # samples() + stop()
    MicAudioSource.kt           # AudioRecord, 16 kHz mono PCM-16
    PipeAudioSource.kt          # Reads RecognizerIntent.EXTRA_AUDIO_SOURCE pipe
  recognizer/
    Recognizer.kt               # Backend interface
    TranscriptionEvent.kt       # Sealed: SpeechStarted/Partial/SpeechEnded/Final/Error
    StubRecognizer.kt           # Phase 1 backend — returns "hello world" at pipe EOF
  service/
    WhisperRecognitionService.kt # extends android.speech.RecognitionService

speech-whisper/                  # Phase 2 (in progress)
  build.gradle.kts               # NDK + CMake, arm64-v8a only, NDK 30.0 pinned
  src/main/cpp/
    CMakeLists.txt               # add_subdirectory(whisper.cpp) + JNI lib
    wristotle_speech.cpp         # JNI bridge: loadModel / transcribe / freeModel
    whisper.cpp/                 # git submodule, pinned to v1.8.4
  src/main/java/com/lazydevs/wristotle/speech/whisper/
    WhisperNative.kt             # JNI external fun decls + defaultThreadCount()
    WhisperRecognizer.kt         # Recognizer impl — buffers to EOF, runs whisper_full
    ModelCatalog.kt              # known Whisper models + HuggingFace URLs
    ModelStorage.kt              # filesDir/whisper-models/ + active model id
    ModelDownloader.kt           # Flow<DownloadEvent>, resumable HTTP, throttled progress
```

### Adding a new command handler

1. Implement `ActionHandler` in `handlers/`:

   ```kotlin
   class WeatherHandler(...) : ActionHandler {
       override fun canHandle(query: String) = query.lowercase().startsWith("weather")
       override suspend fun handle(query: String): String = "Sunny and 72°F"
   }
   ```

2. Register it in `PebbleListenerService.onCreate()`:

   ```kotlin
   registry = HandlerRegistry(listOf(
       CallHandler(this, contacts),
       SmsHandler(this, contacts),
       WeatherHandler(this),
   ))
   ```

More specific prefixes go first — `HandlerRegistry` returns the first match.

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

> **GrapheneOS / privacy-ROM note:** `INTERNET` is auto-granted on stock Android but
> may be denied by default on GrapheneOS and similar ROMs that surface it as a runtime
> permission. If model downloads fail with "unable to resolve host", grant it via
> *Settings → Apps → Wristotle Companion → Permissions → Network*, or from ADB:
> `adb shell pm grant com.lazydevs.wristotle android.permission.INTERNET`.

### Watch dictation quirk

`WhisperRecognitionService` must drain the `RecognizerIntent.EXTRA_AUDIO_SOURCE` pipe to EOF *before* calling `callback.results(...)`. Returning early — even with a valid transcript — produces a malformed `DictationResult` packet on the watch firmware's side and surfaces as a generic "Could not understand. Try again." The `Recognizer` interface bakes this contract in: `TranscriptionEvent.Final` is only emitted after `AudioSource.samples()` completes.

### Phase 2 roadmap

The `:speech-whisper` module brings real on-device transcription:

- **2a — module + NDK/CMake/JNI pipeline** ✅
- **2b — whisper.cpp v1.8.4 submodule + JNI bridge (`loadModel`, `transcribe`, `freeModel`)** ✅
- **2c — model picker UI + on-demand download manager (`ModelCatalog`, `ModelStorage`, `ModelDownloader`, `WhisperModelsCard`)** ✅
- **2d — `WhisperRecognizer` implementing `Recognizer`, wired into `WristotleApplication`, with perf tuning** ✅

### Performance

Measured on a Pixel 10a (Tensor G3) with `tiny.en`:

| Audio length | Inference | Ratio |
|---|---|---|
| 4.4 s | 0.71 s | 0.16× realtime |
| 5.0 s | 0.71 s | 0.14× realtime |
| 15.0 s | 2.11 s | 0.14× realtime |

Model loads ~100 ms (tiny.en) / ~600 ms (base.en) **once** — the loaded handle is cached across sessions in `WristotleApplication`. The recognition service's per-session `close()` is a no-op so reload happens only when the user switches the active model.

The native code is built with `-O3 -DNDEBUG` regardless of gradle's debug/release variant (forced in `speech-whisper/src/main/cpp/CMakeLists.txt`) — whisper.cpp at `-O0` is ~100× realtime, well past any companion app's dictation timeout. Thread count is picked at runtime via `Runtime.availableProcessors() / 2 + 1` clamped to `[2, 8]` so the same APK scales sensibly from quad-cores to 9-core flagships. The encoder's attention window is adaptive: 768 mel frames (~10 s) for short utterances, the default 1500 for anything longer.
