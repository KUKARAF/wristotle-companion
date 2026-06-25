// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.util.Log
import com.lazydevs.wristotle.apps.AppIndex
import com.lazydevs.wristotle.apps.AppIndexDatabase
import com.lazydevs.wristotle.apps.AppIndexer
import com.lazydevs.wristotle.history.ConversationAudioStore
import com.lazydevs.wristotle.history.ConversationDatabase
import com.lazydevs.wristotle.history.ConversationRepository
import com.lazydevs.wristotle.media.ActiveMediaSession
import com.lazydevs.wristotle.messaging.toInfoForSlots
import com.lazydevs.wristotle.nlu.LearningCollector
import com.lazydevs.wristotle.nlu.learning.ExampleBank
import com.lazydevs.wristotle.nlu.learning.NluDatabase
import com.lazydevs.wristotle.phone.ContactsRepository
import com.lazydevs.wristotle.settings.WatchSettingsRepository
import com.lazydevs.wristotle.speech.Recognizers
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentClassifier
import com.lazydevs.wristotle.speech.nlu.IntentClassifiers
import com.lazydevs.wristotle.speech.nlu.NluSettings
import com.lazydevs.wristotle.speech.nlu.StubIntentClassifier
import com.lazydevs.wristotle.speech.nlu.embedding.EmbeddingIntentClassifier
import com.lazydevs.wristotle.speech.nlu.embedding.MiniLmEmbedder
import com.lazydevs.wristotle.speech.nlu.embedding.Tokenizer
import com.lazydevs.wristotle.speech.nlu.embedding.fromContext
import com.lazydevs.wristotle.speech.nlu.model.NluModelStorage
import com.lazydevs.wristotle.speech.nlu.settings.AlarmSettings
import com.lazydevs.wristotle.speech.nlu.settings.ConversationAudioSettings
import com.lazydevs.wristotle.speech.nlu.settings.ConversationSettings
import com.lazydevs.wristotle.speech.nlu.settings.SttProviderMode
import com.lazydevs.wristotle.speech.nlu.settings.localeDefaultTempUnit
import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractorRegistry
import com.lazydevs.wristotle.speech.nlu.slots.CalendarSlots
import com.lazydevs.wristotle.speech.nlu.slots.CallSlots
import com.lazydevs.wristotle.speech.nlu.slots.CancelSlots
import com.lazydevs.wristotle.speech.nlu.slots.CreateEventSlots
import com.lazydevs.wristotle.speech.nlu.slots.FindPhoneSlots
import com.lazydevs.wristotle.speech.nlu.slots.ListRemindersSlots
import com.lazydevs.wristotle.speech.nlu.slots.MediaPlaySlots
import com.lazydevs.wristotle.speech.nlu.slots.MediaSeekSlots
import com.lazydevs.wristotle.speech.nlu.slots.MediaTargetSlots
import com.lazydevs.wristotle.speech.nlu.slots.OpenAppSlots
import com.lazydevs.wristotle.speech.nlu.slots.ReminderSlots
import com.lazydevs.wristotle.speech.nlu.slots.RescheduleSlots
import com.lazydevs.wristotle.speech.recognizer.CompositeRecognizer
import com.lazydevs.wristotle.speech.recognizer.HttpRecognizer
import com.lazydevs.wristotle.speech.recognizer.Recognizer
import com.lazydevs.wristotle.speech.recognizer.StubRecognizer
import com.lazydevs.wristotle.speech.whisper.ModelStorage
import com.lazydevs.wristotle.speech.whisper.WhisperRecognizer
import com.lazydevs.wristotle.storage.kvStore
import com.lazydevs.wristotle.transport.PebbleTransport
import com.lazydevs.wristotle.util.hasPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "WristotleApplication"

/**
 * Process-wide setup hook.
 *
 * Owns:
 * - The single [PebbleTransport] used by both
 *   [com.lazydevs.wristotle.service.PebbleListenerService] (inbound) and
 *   [com.lazydevs.wristotle.service.WatchMessageService] (outbound startup ping).
 *   Without this each service constructs its own
 *   [io.rebble.pebblekit2.client.DefaultPebbleSender] and the two binders can
 *   race on shutdown.
 * - The [Recognizers] provider — resolves to a [WhisperRecognizer] for the
 *   currently active model, or falls back to [StubRecognizer] when no model
 *   is downloaded or activated yet. Cached per model path so the (slow)
 *   model load only happens once per active model.
 */
class WristotleApplication : Application() {

    /** Shared Pebble sender — Application-scoped, lifetime matches the process. */
    lateinit var transport: PebbleTransport
        private set

    /** Conversation history store — Application-scoped, lifetime matches the process. */
    lateinit var conversationRepository: ConversationRepository
        private set

    /** User preferences for the conversation history (retention window). */
    lateinit var conversationSettings: ConversationSettings
        private set

    /** User preference for whether to capture audio per dictation. */
    lateinit var conversationAudioSettings: ConversationAudioSettings
        private set

    /** Manages the bounded conversation-audio directory. */
    lateinit var conversationAudioStore: ConversationAudioStore
        private set

    /** Notes data layer (Phase A — watch-dictated text + optional .wav). */
    lateinit var noteRepository: com.lazydevs.wristotle.notes.NoteRepository
        private set
    lateinit var noteSettings: com.lazydevs.wristotle.speech.nlu.settings.NoteSettings
        private set
    lateinit var notesAudioStore: com.lazydevs.wristotle.notes.NotesAudioStore
        private set

    /** Notes folder sync (Phase A). One [FileSyncSettings] +
     *  [FileSyncCoordinator] per syncable entity scope; conversations
     *  follow when needed. The coordinator is held so the Settings
     *  card's "Sync now" button can call [syncNow]. */
    lateinit var notesSyncSettings: com.lazydevs.wristotle.speech.nlu.settings.FileSyncSettings
        private set
    lateinit var notesSyncCoordinator:
        com.lazydevs.wristotle.sync.FileSyncCoordinator<com.lazydevs.wristotle.notes.Note>
        private set

    /** Conversations folder sync — phase B. Independent scope from
     *  notes; user can pick the same folder OR a different one.
     *  Default granularity = AppendToSingleFile (daily-log shape) since
     *  per-query files would flood any vault. */
    lateinit var conversationsSyncSettings: com.lazydevs.wristotle.speech.nlu.settings.FileSyncSettings
        private set
    lateinit var conversationsSyncCoordinator:
        com.lazydevs.wristotle.sync.FileSyncCoordinator<com.lazydevs.wristotle.history.ConversationEntry>
        private set

    /** Tasks data layer (separate Room DB; checklist items, no due dates). */
    lateinit var taskRepository: com.lazydevs.wristotle.tasks.TaskRepository
        private set
    lateinit var tasksDb: com.lazydevs.wristotle.tasks.TasksDatabase
        private set

    /** Alarms data layer (separate Room DB; per-alarm destination + watch-leg
     *  wireEpoch). Lazy because a user with no alarms never opens the
     *  surface; matches the perf-target-old-phones memory budget. */
    val alarmsDb: com.lazydevs.wristotle.alarms.AlarmsDatabase by lazy {
        com.lazydevs.wristotle.alarms.AlarmsDatabase.get(this)
    }
    val alarmRepository: com.lazydevs.wristotle.alarms.AlarmRepository by lazy {
        com.lazydevs.wristotle.alarms.AlarmRepository(alarmsDb.alarmDao())
    }
    val alarmDispatcher: com.lazydevs.wristotle.alarms.AlarmDispatcher by lazy {
        com.lazydevs.wristotle.alarms.AlarmDispatcher(this, transport, alarmRepository)
    }
    val alarmSettings: AlarmSettings by lazy {
        AlarmSettings(
            kvStore(AlarmSettings.PREFS_NAME),
        )
    }

    /** Persistent-reminder scheduler — owns AlarmManager handles + the
     *  "Persistent reminders" notification channel. Lazy because a user
     *  with no persistent reminders never hits the code path; the
     *  notification channel itself is created up-front in onCreate so the
     *  first nag has a channel to land on. */
    val persistentReminderScheduler:
        com.lazydevs.wristotle.handlers.persistent.PersistentReminderScheduler by lazy {
        com.lazydevs.wristotle.handlers.persistent.PersistentReminderScheduler(this)
    }

    /** Persisted notification log — Phase A wires the DB + write path,
     *  Phase C plumbs the Settings-backed toggle. Lazy so a user who never
     *  flips the toggle pays no Room init cost. */
    val notificationLogDb:
        com.lazydevs.wristotle.notifications.NotificationLogDatabase by lazy {
        com.lazydevs.wristotle.notifications.NotificationLogDatabase.build(this)
    }
    val notificationLogSettings:
        com.lazydevs.wristotle.speech.nlu.settings.NotificationLogSettings by lazy {
        com.lazydevs.wristotle.speech.nlu.settings.NotificationLogSettings(
            kvStore(com.lazydevs.wristotle.speech.nlu.settings.NotificationLogSettings.PREFS_NAME),
        )
    }
    val notificationLogStore:
        com.lazydevs.wristotle.notifications.NotificationLogStore by lazy {
        com.lazydevs.wristotle.notifications.NotificationLogStore(
            dao = notificationLogDb.notificationPostDao(),
            scope = appScope,
            enabledProvider = { notificationLogSettings.enabled.value },
        )
    }

    /** MCP client data layer. Lazy so the Room DB build + first SharedPrefs
     *  read are deferred from cold start to the first Settings-tap / first
     *  AskAgent intent / first backup run — most users never open these
     *  surfaces. Matches the perf-target-old-phones memory budget. */
    val mcpDb: com.lazydevs.wristotle.mcp.McpDatabase by lazy {
        com.lazydevs.wristotle.mcp.McpDatabase.build(this)
    }
    val mcpServerRepository: com.lazydevs.wristotle.mcp.McpServerRepository by lazy {
        com.lazydevs.wristotle.mcp.McpServerRepository(mcpDb.mcpServerDao())
    }

    /** Reminder feature preferences (default offset when no time is spoken). */
    lateinit var reminderSettings: com.lazydevs.wristotle.speech.nlu.settings.ReminderSettings
        private set

    /** Weather feature preferences (unit + provider + OpenWeather API key). */
    lateinit var weatherSettings: com.lazydevs.wristotle.speech.nlu.settings.WeatherSettings
        private set

    /** Sports feature preferences (favorite teams + preferred sport). */
    lateinit var sportSettings: com.lazydevs.wristotle.speech.nlu.settings.SportSettings
        private set

    /** Generic sports data source (sportskapi, ESPN-backed behind a neutral
     *  API) — a shared singleton so its in-memory config cache + background
     *  refresh persist for the process. */
    lateinit var sportSource: com.lazydevs.sportskapi.SportDataSource
        private set

    /** AskAgent preferences — LLM provider + API key + model. Lazy for
     *  the same reason as [mcpDb]: only touched when the user opens the
     *  Settings card, fires an AskAgent intent, or runs a backup. */
    val askAgentSettings: com.lazydevs.wristotle.speech.nlu.settings.AskAgentSettings by lazy {
        com.lazydevs.wristotle.speech.nlu.settings.AskAgentSettings(
            store = kvStore(com.lazydevs.wristotle.speech.nlu.settings.AskAgentSettings.PREFS_NAME),
            http = com.lazydevs.wristotle.http.AndroidHttpClient(),
        )
    }

    /** STT-provider preferences. See `wristotle-companion/stt-providers.md`. */
    val sttProviderSettings: com.lazydevs.wristotle.speech.nlu.settings.SttProviderSettings by lazy {
        com.lazydevs.wristotle.speech.nlu.settings.SttProviderSettings(
            kvStore(com.lazydevs.wristotle.speech.nlu.settings.SttProviderSettings.PREFS_NAME),
        )
    }

    /** Per-card-kind on/off for the watch's full-screen cards (Settings → ⌚
     *  Watch). The handler registry consults [CardSettings.isEnabled] before
     *  forwarding a `card_kind`. */
    val cardSettings: com.lazydevs.wristotle.speech.nlu.settings.CardSettings by lazy {
        com.lazydevs.wristotle.speech.nlu.settings.CardSettings(
            kvStore(com.lazydevs.wristotle.speech.nlu.settings.CardSettings.PREFS_NAME),
        )
    }

    /** Which Morning Brief sections to include (Settings → Reminders →
     *  Morning Brief). Consumed by MorningBriefHandler + the Settings card. */
    val briefSettings: com.lazydevs.wristotle.speech.nlu.settings.MorningBriefSettings by lazy {
        com.lazydevs.wristotle.speech.nlu.settings.MorningBriefSettings(
            kvStore(com.lazydevs.wristotle.speech.nlu.settings.MorningBriefSettings.PREFS_NAME),
        )
    }

    /** TTS-provider preferences for the watch-side TTS feature. Mirrors
     *  the STT shape; CompositeTtsProvider chooses Local vs HTTP per [mode]. */
    val ttsProviderSettings: com.lazydevs.wristotle.speech.nlu.settings.TtsProviderSettings by lazy {
        com.lazydevs.wristotle.speech.nlu.settings.TtsProviderSettings(
            kvStore(com.lazydevs.wristotle.speech.nlu.settings.TtsProviderSettings.PREFS_NAME),
        )
    }

    /** Active TTS provider, rebuilt cheaply per call so the user's
     *  Settings changes take effect immediately without restart. */
    fun buildTtsProvider(): com.lazydevs.wristotle.speech.nlu.tts.TtsProvider =
        com.lazydevs.wristotle.speech.nlu.tts.CompositeTtsProvider.forMode(
            mode = ttsProviderSettings.mode.value,
            local = com.lazydevs.wristotle.tts.LocalTtsProvider(this),
            http = com.lazydevs.wristotle.tts.HttpTtsClient(ttsProviderSettings),
            log = com.lazydevs.wristotle.logging.WristotleLogger,
        )

    /** Primary-only variant — returns just the engine the user picked
     *  as primary, with no fallback wrapper. Used by the Test surface
     *  on the Speech settings card so "Speak via primary only" actually
     *  tests the primary even when the composite would normally fall
     *  back. The mode-to-provider mapping mirrors
     *  [com.lazydevs.wristotle.speech.nlu.tts.CompositeTtsProvider.forMode]
     *  but stops at the first engine — keeping both factories on the
     *  app side so the UI never reaches into provider constructors. */
    fun buildPrimaryTtsProvider(): com.lazydevs.wristotle.speech.nlu.tts.TtsProvider =
        when (ttsProviderSettings.mode.value) {
            com.lazydevs.wristotle.speech.nlu.settings.TtsProviderMode.LOCAL_ONLY,
            com.lazydevs.wristotle.speech.nlu.settings.TtsProviderMode.LOCAL_PRIMARY ->
                com.lazydevs.wristotle.tts.LocalTtsProvider(this)
            com.lazydevs.wristotle.speech.nlu.settings.TtsProviderMode.CLOUD_PRIMARY ->
                com.lazydevs.wristotle.tts.HttpTtsClient(ttsProviderSettings)
        }

    /** First-launch wizard's dismissed flag + reactive surface. Lives
     *  in its own SharedPrefs (`setup_state`) deliberately so it never
     *  travels in backups — see `setup-flow.md`. */
    val setupSettings: com.lazydevs.wristotle.speech.nlu.settings.SetupSettings by lazy {
        com.lazydevs.wristotle.speech.nlu.settings.SetupSettings(
            kvStore(com.lazydevs.wristotle.speech.nlu.settings.SetupSettings.PREFS_NAME),
        )
    }

    /** Pending recommended-setup actions for the Settings → 🌟 Setup
     *  card and the first-launch wizard. Lazy so users who never open
     *  the surface don't pay the dependency cost on cold start; the
     *  screen calls `refresh()` on first compose anyway. */
    val setupHealthProvider: com.lazydevs.wristotle.setup.SetupHealthProvider by lazy {
        com.lazydevs.wristotle.setup.SetupHealthProvider(
            scope = appScope,
            whisperActiveModelId = { modelStorage.activeModelId },
            nluActiveModelId = { nluModelStorage.activeModelId },
            latestAppScanAt = { appIndexDao.latestScanAt() },
            whisperInWatchPath = {
                pebbleCompanionDetector.state.value.whisperAppliesToWatchDictation
            },
            appAliasCount = { aliasStore.all().size },
            contactAliasCount = { contactAliasStore.all().size },
            isSpeechProviderConfigured = {
                sttProviderSettings.mode.value !=
                    com.lazydevs.wristotle.speech.nlu.settings.SttProviderMode.LOCAL_ONLY ||
                    sttProviderSettings.httpBaseUrl.value.isNotEmpty()
            },
            isAskAgentConfigured = {
                askAgentSettings.anthropicApiKey.value.isNotEmpty() ||
                    askAgentSettings.openaiApiKey.value.isNotEmpty()
            },
            hasCorePermissions = {
                this.hasPermission(android.Manifest.permission.READ_CONTACTS) &&
                    this.hasPermission(android.Manifest.permission.SEND_SMS) &&
                    this.hasPermission(android.Manifest.permission.CALL_PHONE) &&
                    this.hasPermission(android.Manifest.permission.RECORD_AUDIO)
            },
            isLowRamDevice = { isLowRamDevice() },
        )
    }

    /** Room database singletons — exposed for the backup/restore feature so
     *  it can pull rows via the existing DAOs (`db.<entity>Dao().allForBackup()`)
     *  and encode them through the per-entity `*Json` codecs. No PRAGMA / WAL
     *  ceremony — the backup format is JSON-per-table, decoupled from the
     *  SQLite file layout entirely. */
    lateinit var conversationDb: com.lazydevs.wristotle.history.ConversationDatabase
        private set
    lateinit var notesDb: com.lazydevs.wristotle.notes.NoteDatabase
        private set
    lateinit var codesDb: com.lazydevs.wristotle.codes.CodeDatabase
        private set
    lateinit var codeRepository: com.lazydevs.wristotle.speech.nlu.codes.CodeRepository
        private set
    lateinit var codeSyncSender: com.lazydevs.wristotle.speech.nlu.codes.CodeSyncSender
        private set
    lateinit var nluDb: com.lazydevs.wristotle.nlu.learning.NluDatabase
        private set

    /**
     * Path of the most-recently-saved audio file, published by the
     * recognizer's [WhisperRecognizer.audioSink] and consumed by
     * [com.lazydevs.wristotle.service.PebbleListenerService] when attaching
     * audio to a freshly-dispatched [com.lazydevs.wristotle.history.ConversationEntry].
     *
     * Volatile so the listener service sees writes from the recognizer
     * thread without an explicit sync. A short race window between
     * back-to-back dictations is acceptable for v1 — wall-clock-wise
     * dictations are seconds apart.
     */
    @Volatile var lastCapturedAudioPath: String? = null

    /** Whisper model storage — visible to the diagnostics exporter. */
    lateinit var modelStorage: ModelStorage
        private set

    /** NLU model storage — visible to the diagnostics exporter. */
    lateinit var nluModelStorage: NluModelStorage
        private set

    /** Example bank backing the NLU classifier. Exposed for the Settings "clear learned" action. */
    lateinit var nluBank: ExampleBank
        private set

    /** NLU preferences (learning toggle). Application-scoped, lifetime = process. */
    lateinit var nluSettings: NluSettings
        private set

    /** Implicit-learning insertion + debounced rebuild. */
    lateinit var learningCollector: LearningCollector
        private set

    /** Slot extractors keyed by intent. Reused by every dispatch in PebbleListenerService. */
    lateinit var slotExtractors: SlotExtractorRegistry
        private set

    /** Active-media-session controller. Shared by every MediaXxxHandler.
     *  Cheap to construct (just system-service handles) so we own it
     *  Application-scoped. Methods inside no-op when Notification
     *  Access isn't granted yet — handlers report that to the user. */
    lateinit var activeMediaSession: ActiveMediaSession
        private set

    /** Installed-app index used by OpenAppHandler and the body-aware
     *  branch of MediaPlayHandler. Populated on demand from the Settings
     *  "Scan installed apps" card; empty until the user first taps it. */
    lateinit var appIndex: AppIndex
        private set

    /** DAO behind [appIndex] — also consumed by [SetupHealthProvider]
     *  to read the most-recent scan timestamp without going through
     *  the indexer. */
    lateinit var appIndexDao: com.lazydevs.wristotle.apps.InstalledAppDao
        private set
    lateinit var aliasStore: com.lazydevs.wristotle.apps.AliasStore
        private set
    lateinit var contactAliasStore: com.lazydevs.wristotle.phone.ContactAliasStore
        private set
    lateinit var appIndexer: AppIndexer
        private set

    /** Stats screen data source. Lazy — first access on Settings → 📊 Stats
     *  open builds the small adapter; the underlying DAOs are already live.
     *  See `:wristotle-core`'s `stats/StatsSource` for the seam. */
    val statsSource: com.lazydevs.wristotle.speech.nlu.stats.StatsSource by lazy {
        com.lazydevs.wristotle.stats.RoomStatsSource(
            conversationDao = conversationDb.conversationDao(),
            noteDao = notesDb.noteDao(),
            taskDao = tasksDb.taskDao(),
            alarmDao = alarmsDb.alarmDao(),
            exampleDao = nluDb.exampleDao(),
            appAliasCount = { aliasStore.all().size },
            contactAliasCount = { contactAliasStore.all().size },
            reminderCount = { com.lazydevs.wristotle.handlers.PinStore(this).all().size },
        )
    }

    /** Diagnostics-export preferences. Lazy — only consulted when the
     *  user actually runs an export from the Settings card. */
    val diagnosticsSettings: com.lazydevs.wristotle.speech.nlu.settings.DiagnosticsSettings by lazy {
        com.lazydevs.wristotle.speech.nlu.settings.DiagnosticsSettings(
            kvStore(com.lazydevs.wristotle.speech.nlu.settings.DiagnosticsSettings.PREFS_NAME),
        )
    }

    /** Which BLE companion is paired (rePebble / microPebble / unknown).
     *  Updated lazily in [PebbleListenerService.onBind] from
     *  Binder.getCallingUid(); falls back to package-presence scan on
     *  cold start. Lets the UI hide / downrank Whisper-related cards
     *  when the active path doesn't go through Android SpeechRecognizer
     *  (rePebble bypasses it; microPebble uses it). */
    lateinit var pebbleCompanionDetector: com.lazydevs.wristotle.transport.PebbleCompanionDetector
        private set

    /** Mirror of the watch's persisted settings, populated lazily via
     *  REQUEST_SETTINGS and updated when the watch sends a fresh snapshot.
     *  Application-scoped so the cache survives a settings-tab close. */
    lateinit var watchSettingsRepository: WatchSettingsRepository
        private set

    /** Decides whether the first launch after a fresh install or an
     *  update should auto-open Settings → ❓ Help. Singleton; the UI
     *  layer pulls once at MainScreen mount via [com.lazydevs.wristotle.help.WhatsNewState.consumeOnce]. */
    lateinit var whatsNewState: com.lazydevs.wristotle.help.WhatsNewState
        private set

    /**
     * Application-scoped scope for fire-and-forget housekeeping (DB pruning, etc).
     * SupervisorJob so one failure doesn't cancel siblings.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Push the saved codes to the watch (fire-and-forget). Called when the watch
     *  connects (COMPANION_PING) so a freshly-launched watch gets the current
     *  codes even if they changed while it was disconnected. */
    fun syncCodesToWatch() {
        appScope.launch { runCatching { codeSyncSender.sync() } }
    }

    /** Cache: modelPath → recognizer. Built on demand by the provider lambda. */
    private val whisperRecognizers = mutableMapOf<String, WhisperRecognizer>()

    /**
     * Single cached intent classifier for the currently-active NLU model path,
     * or null if the user hasn't downloaded + activated one yet. Falls back to
     * [StubIntentClassifier] when null. Synchronization mirrors [whisperRecognizers].
     */
    private var cachedClassifier: Pair<String, EmbeddingIntentClassifier>? = null
    private val classifierLock = Any()

    override fun onCreate() {
        super.onCreate()
        // Install the uncaught-exception handler before anything else
        // so init-time crashes (DB build, model storage, etc.) still
        // land on disk for the next bug-report export. Pure-JVM only —
        // native SEGVs in whisper.cpp / ONNX still tombstone silently.
        com.lazydevs.wristotle.diagnostics.CrashLogStore.install(filesDir)
        transport = PebbleTransport(this)
        modelStorage = ModelStorage(this)
        nluModelStorage = NluModelStorage(this)

        // Persistent reminders need their notification channel before the
        // first nag fires. Idempotent — safe to call on every onCreate.
        persistentReminderScheduler.ensureNotificationChannel()

        conversationDb = ConversationDatabase.build(this)
        conversationSettings = ConversationSettings(
            kvStore(ConversationSettings.PREFS_NAME),
        )
        conversationAudioSettings = ConversationAudioSettings(
            kvStore(ConversationAudioSettings.PREFS_NAME),
        )
        conversationAudioStore = ConversationAudioStore(this)
        conversationRepository = ConversationRepository(
            dao = conversationDb.conversationDao(),
            settings = conversationSettings,
            audioStore = conversationAudioStore,
        )
        // Drop anything past the retention window on startup so storage doesn't
        // grow unbounded if the user uninstalled the app for a while and then
        // came back. Subsequent inserts also prune.
        appScope.launch { conversationRepository.prune() }

        // Notes — Room DB + audio store + retention setting. Prune on startup
        // in case the user lowered the keep-last-N cap while the app was off.
        notesDb = com.lazydevs.wristotle.notes.NoteDatabase.build(this)
        notesAudioStore = com.lazydevs.wristotle.notes.NotesAudioStore(this)
        noteSettings = com.lazydevs.wristotle.speech.nlu.settings.NoteSettings(
            kvStore(com.lazydevs.wristotle.speech.nlu.settings.NoteSettings.PREFS_NAME),
        )
        noteRepository = com.lazydevs.wristotle.notes.NoteRepository(
            dao = notesDb.noteDao(),
            audioStore = notesAudioStore,
            settings = noteSettings,
        )
        appScope.launch { noteRepository.prune() }

        // Saved codes (QR / barcode) — Room DB + repository + watch sync.
        codesDb = com.lazydevs.wristotle.codes.CodeDatabase.build(this)
        codeRepository = com.lazydevs.wristotle.codes.RoomCodeRepository(codesDb.codeDao())
        codeSyncSender = com.lazydevs.wristotle.speech.nlu.codes.CodeSyncSender(codeRepository, transport)
        // Push the codes to the watch whenever they change (and once at startup,
        // via the initial emission), so the watch's offline cache stays current.
        appScope.launch {
            codeRepository.observeAll().collect { runCatching { codeSyncSender.sync() } }
        }

        // Notification log retention — drop rows older than the
        // retention window so the DB stays bounded if the user kept
        // the toggle on for weeks. The brief only ever reads "today"
        // so anything past the window is dead weight. No-op when the
        // toggle is off; the store still has nothing to prune.
        appScope.launch {
            notificationLogStore.prune(
                cutoffEpochMs = System.currentTimeMillis() -
                    com.lazydevs.wristotle.notifications.NotificationLogStore.DEFAULT_RETENTION_MS,
            )
        }

        // Folder sync — per-entity Settings instance + Coordinator running
        // on the long-lived app scope. The coordinator no-ops until the
        // user enables sync + picks a folder; subscription drops cleanly
        // when either flips off.
        notesSyncSettings = com.lazydevs.wristotle.speech.nlu.settings.FileSyncSettings(
            kvStore(com.lazydevs.wristotle.speech.nlu.settings.FileSyncSettings.prefsName("notes"),
            ),
        )
        notesSyncCoordinator = com.lazydevs.wristotle.sync.FileSyncCoordinator(
            context = this,
            settings = notesSyncSettings,
            renderer = com.lazydevs.wristotle.sync.NoteRenderer(),
            entitiesFlow = noteRepository.observeAll(),
            idOf = { it.id.toString() },
            scope = "notes",
            appScope = appScope,
        )
        notesSyncCoordinator.start()

        conversationsSyncSettings = com.lazydevs.wristotle.speech.nlu.settings.FileSyncSettings(
            store = kvStore(com.lazydevs.wristotle.speech.nlu.settings.FileSyncSettings.prefsName("conversations"),
            ),
            defaultGranularity = com.lazydevs.wristotle.speech.nlu.settings.FileSyncGranularity.AppendToSingleFile,
        )
        conversationsSyncCoordinator = com.lazydevs.wristotle.sync.FileSyncCoordinator(
            context = this,
            settings = conversationsSyncSettings,
            renderer = com.lazydevs.wristotle.sync.ConversationRenderer(),
            entitiesFlow = conversationRepository.observeAll(),
            idOf = { it.id.toString() },
            scope = "conversations",
            appScope = appScope,
        )
        conversationsSyncCoordinator.start()

        // Tasks — separate Room DB, no auto-prune (user-managed checklist).
        tasksDb = com.lazydevs.wristotle.tasks.TasksDatabase.build(this)
        taskRepository = com.lazydevs.wristotle.tasks.TaskRepository(tasksDb.taskDao())

        // mcpDb / mcpServerRepository / askAgentSettings / diagnosticsSettings
        // are `by lazy` — first access pays the init.

        reminderSettings = com.lazydevs.wristotle.speech.nlu.settings.ReminderSettings(
            kvStore(com.lazydevs.wristotle.speech.nlu.settings.ReminderSettings.PREFS_NAME),
        )
        weatherSettings = com.lazydevs.wristotle.speech.nlu.settings.WeatherSettings(
            kvStore(com.lazydevs.wristotle.speech.nlu.settings.WeatherSettings.PREFS_NAME),
            localeDefaultProvider = ::localeDefaultTempUnit,
        )
        sportSettings = com.lazydevs.wristotle.speech.nlu.settings.SportSettings(
            kvStore(com.lazydevs.wristotle.speech.nlu.settings.SportSettings.PREFS_NAME),
        )
        // ESPN specifics + remote-config handling live entirely inside
        // sportskapi; we only supply the HTTP + storage seams and the URL of
        // the hosted config (slug/team-index updates land without an app
        // release; a bundled default keeps it working offline).
        sportSource = com.lazydevs.sportskapi.SportsKApi.create(
            http = com.lazydevs.wristotle.sport.SportHttpImpl(),
            store = com.lazydevs.wristotle.sport.SportConfigStoreImpl(
                kvStore(com.lazydevs.wristotle.sport.SportConfigStoreImpl.PREFS_NAME),
            ),
            // The canonical config lives in the sportskapi library repo (it's a
            // library artifact, not Wristotle-specific). Codeberg serves it raw,
            // so hot-fixes go live on git push — no docs-site build / edge cache.
            remoteConfigUrl = "https://codeberg.org/wristotle/sportskapi/raw/branch/main/sports-config.json",
        )

        nluDb = NluDatabase.build(this)
        nluBank = ExampleBank(nluDb.exampleDao())
        // SharedPreferences-backed store using the existing file name so
        // the persisted "learning enabled" toggle survives the upgrade.
        nluSettings = NluSettings(
            kvStore(NluSettings.PREFS_NAME),
        )

        activeMediaSession = ActiveMediaSession(this)

        appIndexDao = AppIndexDatabase.build(this).installedAppDao()
        aliasStore = com.lazydevs.wristotle.apps.AliasStore(this)
        contactAliasStore = com.lazydevs.wristotle.phone.ContactAliasStore(this)
        appIndex = AppIndex(appIndexDao, aliasResolver = aliasStore::resolve)
        appIndexer = AppIndexer(this, appIndexDao, aliasStore)

        pebbleCompanionDetector =
            com.lazydevs.wristotle.transport.PebbleCompanionDetector(this)

        watchSettingsRepository = WatchSettingsRepository(transport, appScope)

        whatsNewState = com.lazydevs.wristotle.help.WhatsNewState(
            context = this,
            currentVersion = BuildConfig.VERSION_NAME,
        )

        // Slot extractors are stateless aside from the contacts dep shared
        // with SendMessageHandler / CallSlots, so building them once at
        // startup is fine. The single MediaSeekSlots instance handles BOTH
        // MediaSeekForward and MediaSeekBackward — direction lives on the
        // intent, magnitude in the slot.
        val contacts = ContactsRepository(this)
        val mediaSeekSlots = MediaSeekSlots()
        val mediaTargetSlots = MediaTargetSlots()
        slotExtractors = SlotExtractorRegistry(mapOf(
            Intent.Call to CallSlots(),
            // Android-side MessagingTarget projects down via toInfo() and
            // the contacts repo's findContact slots into the lambda
            // signature the lifted slot accepts.
            Intent.SendMessage to com.lazydevs.wristotle.speech.nlu.slots.SendMessageSlots(
                targets = com.lazydevs.wristotle.messaging.MessagingTargets.NAMED.map { it.toInfoForSlots() },
                smsDisplayName = com.lazydevs.wristotle.messaging.MessagingTargets.Sms.displayName,
                findContact = contacts::findContact,
            ),
            Intent.Reminder to ReminderSlots(
                timeParser = com.lazydevs.wristotle.handlers.PrettyTimeTimeParser,
                defaultOffsetMinProvider = { reminderSettings.defaultOffsetMin.value },
            ),
            Intent.Cancel to CancelSlots(),
            Intent.ListReminders to ListRemindersSlots(com.lazydevs.wristotle.handlers.PrettyTimeTimeParser),
            Intent.Reschedule to RescheduleSlots(com.lazydevs.wristotle.handlers.PrettyTimeTimeParser),
            Intent.FindPhone to FindPhoneSlots(),
            Intent.MediaPlay to MediaPlaySlots(),
            Intent.MediaPause to mediaTargetSlots,
            Intent.MediaNext to mediaTargetSlots,
            Intent.MediaPrevious to mediaTargetSlots,
            Intent.MediaSeekForward to mediaSeekSlots,
            Intent.MediaSeekBackward to mediaSeekSlots,
            Intent.OpenApp to OpenAppSlots(),
            Intent.Calendar to CalendarSlots(com.lazydevs.wristotle.handlers.PrettyTimeTimeParser),
            Intent.CreateEvent to CreateEventSlots(com.lazydevs.wristotle.handlers.PrettyTimeTimeParser),
            Intent.Note to com.lazydevs.wristotle.speech.nlu.slots.NoteSlots(),
            Intent.AppendNote to com.lazydevs.wristotle.speech.nlu.slots.AppendNoteSlots(),
            Intent.AddTask to com.lazydevs.wristotle.speech.nlu.slots.AddTaskSlots(),
            Intent.ListTasks to com.lazydevs.wristotle.speech.nlu.slots.ListTasksSlots(),
            // CompleteTask + DeleteTask share the same target-extraction logic
            // — the slot extractor strips both complete-style and delete-style
            // verbs; the handlers differ only in what they DO with the matched task.
            Intent.CompleteTask to com.lazydevs.wristotle.speech.nlu.slots.CompleteTaskSlots(),
            Intent.DeleteTask to com.lazydevs.wristotle.speech.nlu.slots.CompleteTaskSlots(),
            Intent.CancelAlarm to com.lazydevs.wristotle.speech.nlu.slots.CancelAlarmSlots(com.lazydevs.wristotle.handlers.PrettyTimeTimeParser),
            Intent.SetAlarm to com.lazydevs.wristotle.speech.nlu.slots.SetAlarmSlots(com.lazydevs.wristotle.handlers.PrettyTimeTimeParser),
            Intent.SetTimer to com.lazydevs.wristotle.speech.nlu.slots.SetTimerSlots(),
            Intent.WorldTime to com.lazydevs.wristotle.speech.nlu.slots.WorldTimeSlots(),
            Intent.Calculate to com.lazydevs.wristotle.speech.nlu.slots.CalculateSlots(),
            Intent.Weather to com.lazydevs.wristotle.speech.nlu.slots.WeatherSlots(),
            Intent.SportScore to com.lazydevs.wristotle.speech.nlu.slots.SportSlots(),
            Intent.AskAgent to com.lazydevs.wristotle.speech.nlu.slots.AskAgentSlots(
                extrasProvider = { askAgentSettings.customTriggers.value },
            ),
            Intent.MorningBrief to com.lazydevs.wristotle.speech.nlu.slots.MorningBriefSlots(),
            Intent.ShowCode to com.lazydevs.wristotle.speech.nlu.slots.ShowCodeSlots(),
        ))

        learningCollector = LearningCollector(
            scope = appScope,
            bank = nluBank,
            classifierProvider = { (cachedClassifier?.second) },
            settings = nluSettings,
        )

        Recognizers.provider = provider@{ _ ->
            buildRecognizerForSession()
        }

        // Audio capture is wired above the Recognizer so it fires once
        // per session regardless of which inner recognizer ran. See
        // wristotle-companion/stt-providers.md for the architecture.
        com.lazydevs.wristotle.speech.AudioSinks.provider = sink@{
            if (!conversationAudioSettings.captureEnabled.value) return@sink null
            return@sink { samples ->
                val saved = conversationAudioStore.save(samples, sampleRate = 16_000)
                if (saved != null) lastCapturedAudioPath = saved.absolutePath
            }
        }

        IntentClassifiers.provider = { _ -> getOrCreateClassifier() }

        // Pre-warm only on devices Android doesn't flag as low-RAM. On
        // capable hardware the ~500 ms tokenizer + seed-embedding burst (NLU)
        // and the Whisper cold-start inference are worth absorbing now so the
        // first user query / dictation feels instant. On low-RAM 2019-era
        // hardware that same burst competes with other apps initialising
        // during cold-boot and risks an OOM kill — and the heavier Whisper
        // model would sit resident for nothing if the user never dictates —
        // so let the first use pay the cost lazily instead.
        if (!isLowRamDevice()) {
            appScope.launch {
                (getOrCreateClassifier() as? EmbeddingIntentClassifier)?.warmUp()
            }
            // Whisper: prime the active model's compute graph so the first
            // dictation doesn't blow the watch's dictation timeout (see #93).
            // Skip under Core Devices — its own engine transcribes watch
            // dictation, so our Whisper is never in the path and loading it
            // would just burn CPU + hold the model resident for nothing.
            // (Initial signal is the synchronous package scan; refined later
            // by binder UID. Unknown → warm, the safe default.) A no-op too
            // when no model is active (activeModelPath() is null).
            appScope.launch {
                if (pebbleCompanionDetector.state.value.whisperAppliesToWatchDictation) {
                    modelStorage.activeModelPath()?.let { path ->
                        (getOrCreateRecognizer(path) as? WhisperRecognizer)?.warmUp()
                    }
                }
            }
        }
    }

    override fun onTerminate() {
        // Rarely called in production (only in emulator / test). Best-effort cleanup.
        transport.close()
        synchronized(whisperRecognizers) {
            whisperRecognizers.values.forEach { it.release() }
            whisperRecognizers.clear()
        }
        super.onTerminate()
    }

    /**
     * Evicts a cached recognizer by model file path and releases its native
     * memory. Called from the UI when the user deletes a downloaded model —
     * without this, the cached recognizer's loaded model handle stays in
     * memory until the next active-model switch, and re-downloading the same
     * model returns the stale recognizer instead of loading the new file.
     */
    fun evictRecognizer(modelPath: String) {
        synchronized(whisperRecognizers) {
            whisperRecognizers.remove(modelPath)?.also {
                Log.d(TAG, "evicting recognizer for $modelPath (model deleted)")
                it.release()
            }
        }
    }

    /**
     * Resolves the right [Recognizer] for the current session based on
     * [sttProviderSettings]. Called per dictation so a settings edit
     * picks up without an app restart. HTTP recognizers are stateless
     * + cheap to construct, so they're rebuilt each call rather than
     * cached (keeps Settings edits live without invalidation).
     */
    private fun buildRecognizerForSession(): Recognizer {
        // Resolve Whisper lazily so CLOUD_PRIMARY without a fallback model
        // doesn't pay the cache lookup + sync overhead.
        fun whisper(): Recognizer? =
            modelStorage.activeModelPath()?.let { getOrCreateRecognizer(it) }
        return when (sttProviderSettings.mode.value) {
            SttProviderMode.LOCAL_ONLY -> whisper() ?: StubRecognizer()
            SttProviderMode.LOCAL_PRIMARY -> whisper()?.let {
                CompositeRecognizer(primary = it, secondary = buildHttpRecognizer())
            } ?: buildHttpRecognizer()
            SttProviderMode.CLOUD_PRIMARY -> whisper()?.let {
                CompositeRecognizer(primary = buildHttpRecognizer(), secondary = it)
            } ?: buildHttpRecognizer()
        }
    }

    /** Reads the current HTTP config straight from [sttProviderSettings]
     *  and returns a fresh [HttpRecognizer]. Always returns a recognizer
     *  even if the config is blank — the recognizer's own validation
     *  surfaces a typed `ERROR_CLIENT` event, which the composite then
     *  treats as a fallback trigger. */
    private fun buildHttpRecognizer(): HttpRecognizer = HttpRecognizer(
        baseUrl = sttProviderSettings.httpBaseUrl.value,
        apiKey = sttProviderSettings.httpApiKey.value,
        model = sttProviderSettings.httpModel.value,
    )

    /**
     * Returns a cached [WhisperRecognizer] for [path], creating one if needed.
     * Frees any cached recognizers for *other* paths via [WhisperRecognizer.release]
     * — that's how a model switch reclaims the previous model's native memory
     * without an app restart. The recognition service's per-session
     * [Recognizer.close] is a no-op on `WhisperRecognizer`, so the model stays
     * loaded across sessions and we pay the ~600 ms load cost only once.
     */
    private fun getOrCreateRecognizer(path: String): Recognizer = synchronized(whisperRecognizers) {
        val stale = whisperRecognizers.keys.filter { it != path }
        for (oldPath in stale) {
            Log.d(TAG, "releasing recognizer for inactive model: $oldPath")
            whisperRecognizers.remove(oldPath)?.release()
        }
        whisperRecognizers.getOrPut(path) {
            Log.d(TAG, "creating recognizer for active model: $path")
            WhisperRecognizer(modelPath = path)
        }
    }

    /**
     * Returns the cached [EmbeddingIntentClassifier] for the currently-active
     * NLU model, building it lazily on first access. Falls back to
     * [StubIntentClassifier] when no model is active *or* when Android
     * reports this is a low-RAM device — the embedder + ONNX session add
     * ~80–100 MB of resident memory and the prefix-match path that the
     * stub triggers is acceptable degradation on memory-constrained
     * hardware. Tokenizer + embedder are heavy; cache by path so a
     * model switch tears down the previous one.
     */
    private fun getOrCreateClassifier(): IntentClassifier {
        if (isLowRamDevice()) {
            Log.d(TAG, "low-RAM device — skipping NLU classifier, using stub")
            return StubIntentClassifier()
        }
        val path = nluModelStorage.activeModelPath() ?: return StubIntentClassifier()
        return synchronized(classifierLock) {
            val cached = cachedClassifier
            if (cached != null && cached.first == path) return@synchronized cached.second

            // Stale (active model switched) — close the old ONNX session before
            // building anew, so its ~80–100 MB native memory is freed promptly
            // rather than lingering until GC.
            cached?.second?.also {
                Log.d(TAG, "releasing intent classifier for inactive model: ${cached.first}")
                runCatching { it.close() }
                    .onFailure { e -> Log.w(TAG, "closing stale classifier failed", e) }
            }

            Log.d(TAG, "creating intent classifier for active model: $path")
            val tokenizer = Tokenizer.fromContext(this, com.lazydevs.wristotle.speech.nlu.R.raw.minilm_vocab)
            val embedder = MiniLmEmbedder(modelPath = path, tokenizer = tokenizer)
            // The classifier no longer takes the Room-backed ExampleBank
            // directly — it asks for learned rows via a pure lambda so
            // the :speech-nlu module can stay free of Android persistence.
            // ExampleBank.loadLearned() returns the LearnedExample shape
            // the classifier expects.
            val classifier = EmbeddingIntentClassifier(
                embedder = embedder,
                loadLearned = nluBank::loadLearned,
                // commonMain has no android.util.Log; pipe debug lines
                // through the same logger here so on-device debugging
                // doesn't lose the warm-up + rebuild traces.
                log = { msg -> Log.d("EmbeddingIntentClassifier", msg) },
            )
            cachedClassifier = path to classifier
            classifier
        }
    }

    /**
     * Android's own signal that the device should run in low-memory mode.
     * True on phones with ≲1 GB RAM and on emulators with the equivalent
     * config. When set, we skip the embedder + ONNX session (~80–100 MB
     * resident) and fall back to the prefix-match stub classifier.
     */
    private fun isLowRamDevice(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return am?.isLowRamDevice == true
    }
}