package com.lazydevs.wristotle

import android.app.Application
import android.util.Log
import com.lazydevs.wristotle.history.ConversationDatabase
import com.lazydevs.wristotle.history.ConversationRepository
import com.lazydevs.wristotle.history.ConversationSettings
import com.lazydevs.wristotle.nlu.LearningCollector
import com.lazydevs.wristotle.nlu.NluSettings
import com.lazydevs.wristotle.nlu.slots.CallSlots
import com.lazydevs.wristotle.nlu.slots.CancelSlots
import com.lazydevs.wristotle.nlu.slots.FindPhoneSlots
import com.lazydevs.wristotle.nlu.slots.ReminderSlots
import com.lazydevs.wristotle.nlu.slots.SmsSlots
import com.lazydevs.wristotle.phone.ContactsRepository
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
import com.lazydevs.wristotle.speech.recognizer.Recognizer
import com.lazydevs.wristotle.speech.recognizer.StubRecognizer
import com.lazydevs.wristotle.speech.whisper.ModelStorage
import com.lazydevs.wristotle.speech.whisper.WhisperRecognizer
import com.lazydevs.wristotle.transport.PebbleTransport
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

    private lateinit var modelStorage: ModelStorage
    private lateinit var nluModelStorage: NluModelStorage

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

        val database = ConversationDatabase.build(this)
        conversationSettings = ConversationSettings(this)
        conversationRepository = ConversationRepository(database.conversationDao(), conversationSettings)
        // Drop anything past the retention window on startup so storage doesn't
        // grow unbounded if the user uninstalled the app for a while and then
        // came back. Subsequent inserts also prune.
        appScope.launch { conversationRepository.prune() }

        nluBank = ExampleBank(NluDatabase.build(this).exampleDao())
        nluSettings = NluSettings(this)

        // Slot extractors are stateless aside from the contacts dep shared with
        // SmsHandler, so building them once at startup is fine.
        val contacts = ContactsRepository(this)
        slotExtractors = SlotExtractorRegistry(mapOf(
            Intent.Call to CallSlots(),
            Intent.Sms to SmsSlots(contacts),
            Intent.Reminder to ReminderSlots(),
            Intent.Cancel to CancelSlots(),
            Intent.FindPhone to FindPhoneSlots(),
        ))

        learningCollector = LearningCollector(
            scope = appScope,
            bank = nluBank,
            classifierProvider = { (cachedClassifier?.second) },
            settings = nluSettings,
        )

        Recognizers.provider = provider@{ _ ->
            val path = modelStorage.activeModelPath()
                ?: return@provider StubRecognizer()
            getOrCreateRecognizer(path)
        }

        IntentClassifiers.provider = { _ -> getOrCreateClassifier() }

        // Pre-warm the classifier if a model is already active so the first
        // user query doesn't pay the ~500 ms tokenizer + seed-embedding cost.
        appScope.launch {
            (getOrCreateClassifier() as? EmbeddingIntentClassifier)?.warmUp()
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
     * [StubIntentClassifier] when no model is active. Tokenizer + embedder
     * are heavy; cache by path so a model switch tears down the previous one.
     */
    private fun getOrCreateClassifier(): IntentClassifier {
        val path = nluModelStorage.activeModelPath() ?: return StubIntentClassifier()
        return synchronized(classifierLock) {
            val cached = cachedClassifier
            if (cached != null && cached.first == path) return@synchronized cached.second

            // Stale (active model switched) — close the old session before building anew.
            cached?.second?.also {
                Log.d(TAG, "releasing intent classifier for inactive model: ${cached.first}")
                // EmbeddingIntentClassifier doesn't own anything closeable directly;
                // the embedder it holds does. For now, leave embedder lifecycle to
                // GC since model-switch is rare and the runtime is process-scoped.
            }

            Log.d(TAG, "creating intent classifier for active model: $path")
            val tokenizer = Tokenizer.fromContext(this, com.lazydevs.wristotle.speech.nlu.R.raw.minilm_vocab)
            val embedder = MiniLmEmbedder(modelPath = path)
            val classifier = EmbeddingIntentClassifier(embedder, tokenizer, nluBank)
            cachedClassifier = path to classifier
            classifier
        }
    }
}
