package com.lazydevs.wristotle

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.util.Log
import com.lazydevs.wristotle.apps.AppIndex
import com.lazydevs.wristotle.apps.AppIndexDatabase
import com.lazydevs.wristotle.apps.AppIndexer
import com.lazydevs.wristotle.history.ConversationAudioSettings
import com.lazydevs.wristotle.history.ConversationAudioStore
import com.lazydevs.wristotle.history.ConversationDatabase
import com.lazydevs.wristotle.history.ConversationRepository
import com.lazydevs.wristotle.history.ConversationSettings
import com.lazydevs.wristotle.media.ActiveMediaSession
import com.lazydevs.wristotle.nlu.LearningCollector
import com.lazydevs.wristotle.nlu.NluSettings
import com.lazydevs.wristotle.nlu.slots.CalendarSlots
import com.lazydevs.wristotle.nlu.slots.CallSlots
import com.lazydevs.wristotle.nlu.slots.CancelSlots
import com.lazydevs.wristotle.nlu.slots.CreateEventSlots
import com.lazydevs.wristotle.nlu.slots.FindPhoneSlots
import com.lazydevs.wristotle.nlu.slots.ListRemindersSlots
import com.lazydevs.wristotle.nlu.slots.MediaPlaySlots
import com.lazydevs.wristotle.nlu.slots.MediaSeekSlots
import com.lazydevs.wristotle.nlu.slots.MediaTargetSlots
import com.lazydevs.wristotle.nlu.slots.OpenAppSlots
import com.lazydevs.wristotle.nlu.slots.ReminderSlots
import com.lazydevs.wristotle.nlu.slots.RescheduleSlots
import com.lazydevs.wristotle.phone.ContactsRepository
import com.lazydevs.wristotle.settings.WatchSettingsRepository
import com.lazydevs.wristotle.speech.Recognizers
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentClassifier
import com.lazydevs.wristotle.speech.nlu.IntentClassifiers
import com.lazydevs.wristotle.speech.nlu.StubIntentClassifier
import com.lazydevs.wristotle.speech.nlu.bank.ExampleBank
import com.lazydevs.wristotle.speech.nlu.bank.NluDatabase
import com.lazydevs.wristotle.speech.nlu.embedding.EmbeddingIntentClassifier
import com.lazydevs.wristotle.speech.nlu.embedding.MiniLmEmbedder
import com.lazydevs.wristotle.speech.nlu.embedding.Tokenizer
import com.lazydevs.wristotle.speech.nlu.model.NluModelStorage
import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractorRegistry
import com.lazydevs.wristotle.speech.recognizer.CompositeRecognizer
import com.lazydevs.wristotle.speech.recognizer.HttpRecognizer
import com.lazydevs.wristotle.speech.recognizer.Recognizer
import com.lazydevs.wristotle.speech.recognizer.StubRecognizer
import com.lazydevs.wristotle.stt.SttProviderMode
import com.lazydevs.wristotle.speech.whisper.ModelStorage
import com.lazydevs.wristotle.speech.whisper.WhisperRecognizer
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
    lateinit var noteSettings: com.lazydevs.wristotle.notes.NoteSettings
        private set
    lateinit var notesAudioStore: com.lazydevs.wristotle.notes.NotesAudioStore
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
    val alarmSettings: com.lazydevs.wristotle.alarms.AlarmSettings by lazy {
        com.lazydevs.wristotle.alarms.AlarmSettings(this)
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
    lateinit var reminderSettings: com.lazydevs.wristotle.handlers.ReminderSettings
        private set

    /** Weather feature preferences (unit + provider + OpenWeather API key). */
    lateinit var weatherSettings: com.lazydevs.wristotle.settings.WeatherSettings
        private set

    /** AskAgent preferences — LLM provider + API key + model. Lazy for
     *  the same reason as [mcpDb]: only touched when the user opens the
     *  Settings card, fires an AskAgent intent, or runs a backup. */
    val askAgentSettings: com.lazydevs.wristotle.agent.AskAgentSettings by lazy {
        com.lazydevs.wristotle.agent.AskAgentSettings(this)
    }

    /** STT-provider preferences. See `wristotle-companion/stt-providers.md`. */
    val sttProviderSettings: com.lazydevs.wristotle.stt.SttProviderSettings by lazy {
        com.lazydevs.wristotle.stt.SttProviderSettings(this)
    }

    /** First-launch wizard's dismissed flag + reactive surface. Lives
     *  in its own SharedPrefs (`setup_state`) deliberately so it never
     *  travels in backups — see `setup-flow.md`. */
    val setupSettings: com.lazydevs.wristotle.setup.SetupSettings by lazy {
        com.lazydevs.wristotle.setup.SetupSettings(this)
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
                    com.lazydevs.wristotle.stt.SttProviderMode.LOCAL_ONLY ||
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
    lateinit var nluDb: com.lazydevs.wristotle.speech.nlu.bank.NluDatabase
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

    /** Diagnostics-export preferences. Lazy — only consulted when the
     *  user actually runs an export from the Settings card. */
    val diagnosticsSettings: com.lazydevs.wristotle.diagnostics.DiagnosticsSettings by lazy {
        com.lazydevs.wristotle.diagnostics.DiagnosticsSettings(this)
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
        transport = PebbleTransport(this)
        modelStorage = ModelStorage(this)
        nluModelStorage = NluModelStorage(this)

        conversationDb = ConversationDatabase.build(this)
        conversationSettings = ConversationSettings(this)
        conversationAudioSettings = ConversationAudioSettings(this)
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
        noteSettings = com.lazydevs.wristotle.notes.NoteSettings(this)
        noteRepository = com.lazydevs.wristotle.notes.NoteRepository(
            dao = notesDb.noteDao(),
            audioStore = notesAudioStore,
            settings = noteSettings,
        )
        appScope.launch { noteRepository.prune() }

        // Tasks — separate Room DB, no auto-prune (user-managed checklist).
        tasksDb = com.lazydevs.wristotle.tasks.TasksDatabase.build(this)
        taskRepository = com.lazydevs.wristotle.tasks.TaskRepository(tasksDb.taskDao())

        // mcpDb / mcpServerRepository / askAgentSettings / diagnosticsSettings
        // are `by lazy` — first access pays the init.

        reminderSettings = com.lazydevs.wristotle.handlers.ReminderSettings(this)
        weatherSettings = com.lazydevs.wristotle.settings.WeatherSettings(this)

        nluDb = NluDatabase.build(this)
        nluBank = ExampleBank(nluDb.exampleDao())
        nluSettings = NluSettings(this)

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
            Intent.SendMessage to com.lazydevs.wristotle.nlu.slots.SendMessageSlots(contacts),
            Intent.Reminder to ReminderSlots(
                defaultOffsetMinProvider = { reminderSettings.defaultOffsetMin.value },
            ),
            Intent.Cancel to CancelSlots(),
            Intent.ListReminders to ListRemindersSlots(),
            Intent.Reschedule to RescheduleSlots(),
            Intent.FindPhone to FindPhoneSlots(),
            Intent.MediaPlay to MediaPlaySlots(),
            Intent.MediaPause to mediaTargetSlots,
            Intent.MediaNext to mediaTargetSlots,
            Intent.MediaPrevious to mediaTargetSlots,
            Intent.MediaSeekForward to mediaSeekSlots,
            Intent.MediaSeekBackward to mediaSeekSlots,
            Intent.OpenApp to OpenAppSlots(),
            Intent.Calendar to CalendarSlots(),
            Intent.CreateEvent to CreateEventSlots(),
            Intent.Note to com.lazydevs.wristotle.nlu.slots.NoteSlots(),
            Intent.AppendNote to com.lazydevs.wristotle.nlu.slots.AppendNoteSlots(),
            Intent.AddTask to com.lazydevs.wristotle.nlu.slots.AddTaskSlots(),
            Intent.ListTasks to com.lazydevs.wristotle.nlu.slots.ListTasksSlots(),
            // CompleteTask + DeleteTask share the same target-extraction logic
            // — the slot extractor strips both complete-style and delete-style
            // verbs; the handlers differ only in what they DO with the matched task.
            Intent.CompleteTask to com.lazydevs.wristotle.nlu.slots.CompleteTaskSlots(),
            Intent.DeleteTask to com.lazydevs.wristotle.nlu.slots.CompleteTaskSlots(),
            Intent.CancelAlarm to com.lazydevs.wristotle.nlu.slots.CancelAlarmSlots(),
            Intent.SetAlarm to com.lazydevs.wristotle.nlu.slots.SetAlarmSlots(),
            Intent.SetTimer to com.lazydevs.wristotle.nlu.slots.SetTimerSlots(),
            Intent.WorldTime to com.lazydevs.wristotle.nlu.slots.WorldTimeSlots(),
            Intent.Calculate to com.lazydevs.wristotle.nlu.slots.CalculateSlots(),
            Intent.Weather to com.lazydevs.wristotle.nlu.slots.WeatherSlots(),
            Intent.AskAgent to com.lazydevs.wristotle.nlu.slots.AskAgentSlots(
                extrasProvider = { askAgentSettings.customTriggers.value },
            ),
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
            val classifier = EmbeddingIntentClassifier(embedder, nluBank)
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
