package com.lazydevs.wristotle

import android.app.Application
import android.util.Log
import com.lazydevs.wristotle.history.ConversationDatabase
import com.lazydevs.wristotle.history.ConversationRepository
import com.lazydevs.wristotle.speech.Recognizers
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

    private lateinit var modelStorage: ModelStorage

    /**
     * Application-scoped scope for fire-and-forget housekeeping (DB pruning, etc).
     * SupervisorJob so one failure doesn't cancel siblings.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Cache: modelPath → recognizer. Built on demand by the provider lambda. */
    private val whisperRecognizers = mutableMapOf<String, WhisperRecognizer>()

    override fun onCreate() {
        super.onCreate()
        transport = PebbleTransport(this)
        modelStorage = ModelStorage(this)

        val database = ConversationDatabase.build(this)
        conversationRepository = ConversationRepository(database.conversationDao())
        // Drop anything past the retention window on startup so storage doesn't
        // grow unbounded if the user uninstalled the app for a while and then
        // came back. Subsequent inserts also prune.
        appScope.launch { conversationRepository.prune() }

        Recognizers.provider = provider@{ _ ->
            val path = modelStorage.activeModelPath()
                ?: return@provider StubRecognizer()
            getOrCreateRecognizer(path)
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
}
