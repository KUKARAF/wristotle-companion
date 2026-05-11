# Wristotle Companion

Android companion app for the [Wristotle](../Wristotle) Pebble watch app. Runs as a foreground service and bridges the watch to Android phone capabilities — calls, SMS, and contacts — via PebbleKit AppMessage.

## How it works

The watch sends a raw voice transcription as a `companion_query` message. The companion matches it against registered handlers (call, SMS) and sends back a `companion_response` string for the watch to display.

## Prerequisites

- Android Studio
- Android SDK (min API 24)
- A Pebble watch with the [Wristotle](../Wristotle) app installed
- The rePebble app (or Pebble app) running on the phone

## Build & run

1. Clone this repo and open in Android Studio.
2. Build and install on your Android device.
3. Launch the app and grant the requested permissions (Contacts, Phone, SMS, Notifications).
4. The service starts automatically and stays running in the background.

## Permissions

| Permission | Purpose |
|-----------|---------|
| `READ_CONTACTS` | Contact lookup by name |
| `CALL_PHONE` | Place calls |
| `SEND_SMS` | Send text messages |
| `POST_NOTIFICATIONS` | Foreground service notification (Android 13+) |

## Project structure

```
app/src/main/java/com/lazydevs/wristotle/
  MainActivity.kt               # Starts service, requests permissions
  handlers/
    ActionHandler.kt            # Interface: canHandle() + handle()
    HandlerRegistry.kt          # Dispatches to first matching handler
    CallHandler.kt              # "call/dial [name]"
    SmsHandler.kt               # "text/message [name] [body]"
  phone/
    ContactsRepository.kt       # Contact lookup
  service/
    WatchMessageService.kt      # Foreground service — PebbleKit receiver + dispatch
  transport/
    MessageKeys.kt              # AppMessage key indices
    PebbleTransport.kt          # PebbleKit send/receive wrapper
  ui/
    MainScreen.kt               # Permission status (Compose)
    MainViewModel.kt            # Permission state
```

## Adding a new capability

1. Create `handlers/YourHandler.kt` implementing `ActionHandler`.
2. Register it in `WatchMessageService.onCreate()`:
   ```kotlin
   registry = HandlerRegistry(listOf(
       CallHandler(this, contacts),
       SmsHandler(this, contacts),
       YourHandler(this, ...),
   ))
   ```
