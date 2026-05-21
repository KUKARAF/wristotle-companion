package com.lazydevs.wristotle.service

import com.lazydevs.wristotle.logging.WristotleLog as Log
import com.lazydevs.wristotle.AppConstants
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.handlers.CalendarHandler
import com.lazydevs.wristotle.handlers.CallHandler
import com.lazydevs.wristotle.handlers.CancelReminderHandler
import com.lazydevs.wristotle.handlers.CreateEventHandler
import com.lazydevs.wristotle.handlers.FindPhoneHandler
import com.lazydevs.wristotle.handlers.HandlerRegistry
import com.lazydevs.wristotle.handlers.HandlerRegistry.Companion.isSuccessResponse
import com.lazydevs.wristotle.handlers.ListRemindersHandler
import com.lazydevs.wristotle.handlers.MediaNextHandler
import com.lazydevs.wristotle.handlers.MediaPauseHandler
import com.lazydevs.wristotle.handlers.MediaPlayHandler
import com.lazydevs.wristotle.handlers.MediaPlayPauseHandler
import com.lazydevs.wristotle.handlers.MediaPreviousHandler
import com.lazydevs.wristotle.handlers.MediaSeekHandler
import com.lazydevs.wristotle.handlers.OpenAppHandler
import com.lazydevs.wristotle.handlers.ReminderHandler
import com.lazydevs.wristotle.handlers.SmsHandler
import com.lazydevs.wristotle.history.ConversationEntry
import com.lazydevs.wristotle.history.ConversationRepository
import com.lazydevs.wristotle.nlu.LearningCollector
import com.lazydevs.wristotle.nlu.NluSettings
import com.lazydevs.wristotle.nlu.PrefixHints
import com.lazydevs.wristotle.phone.CalendarRepository
import com.lazydevs.wristotle.phone.ContactsRepository
import com.lazydevs.wristotle.settings.WatchSettingsRepository
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentClassifier
import com.lazydevs.wristotle.speech.nlu.IntentClassifiers
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractorRegistry
import com.lazydevs.wristotle.transport.MessageKeys
import com.lazydevs.wristotle.transport.PebbleTransport
import com.lazydevs.wristotle.transport.int32
import com.lazydevs.wristotle.transport.text
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.launch
import java.util.UUID
import android.content.Intent as AndroidIntent
import android.os.Binder
import android.os.IBinder

/**
 * Receives AppMessages from the Pebble watch via rePebble/microPebble.
 *
 * Phase 3 routing model: every voice query (REMINDER_QUERY / CANCEL_QUERY /
 * COMPANION_QUERY) is run through the NLU classifier, the slot extractor for
 * the chosen intent populates structured parameters, then a 1:1
 * intent→handler dispatch executes the action. Watch-pre-tagged queries
 * (REMINDER_QUERY, CANCEL_QUERY) override the classifier's intent with the
 * watch hint so today's behaviour is preserved exactly even if the
 * classifier disagrees; responses are sent back over the matching legacy
 * channel for back-compat with existing watch firmware.
 */
class PebbleListenerService : BasePebbleListenerService() {

    private lateinit var transport: PebbleTransport
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var registry: HandlerRegistry
    private lateinit var intentClassifier: IntentClassifier
    private lateinit var slotExtractors: SlotExtractorRegistry
    private lateinit var nluSettings: NluSettings
    private lateinit var learningCollector: LearningCollector
    private lateinit var watchSettingsRepository: WatchSettingsRepository

    override fun onCreate() {
        super.onCreate()
        // Note: the actual BLE companion (rePebble vs microPebble) is
        // captured in onBind via Binder.getCallingUid; see the override
        // below + PebbleCompanionDetector.
        Log.d(TAG, "PebbleListenerService onCreate")
        val app = application as WristotleApplication
        transport = app.transport
        conversationRepository = app.conversationRepository
        intentClassifier = IntentClassifiers.provider(this)
        slotExtractors = app.slotExtractors
        nluSettings = app.nluSettings
        learningCollector = app.learningCollector
        watchSettingsRepository = app.watchSettingsRepository

        val contacts = ContactsRepository(this)
        val media = app.activeMediaSession
        val appIndex = app.appIndex
        val calendarRepo = CalendarRepository(this)
        registry = HandlerRegistry(listOf(
            CallHandler(this, contacts),
            SmsHandler(this, contacts),
            ReminderHandler(this, transport),
            CancelReminderHandler(this, transport),
            ListRemindersHandler(this),
            FindPhoneHandler(),
            MediaPlayHandler(this, media, appIndex),
            MediaPauseHandler(this, media, appIndex),
            MediaPlayPauseHandler(media),
            MediaNextHandler(this, media, appIndex),
            MediaPreviousHandler(this, media, appIndex),
            MediaSeekHandler(media, intent = com.lazydevs.wristotle.speech.nlu.Intent.MediaSeekForward),
            MediaSeekHandler(media, intent = com.lazydevs.wristotle.speech.nlu.Intent.MediaSeekBackward),
            OpenAppHandler(this, appIndex),
            CalendarHandler(calendarRepo),
            CreateEventHandler(calendarRepo),
        ))
    }

    // Transport is Application-owned; no close in onDestroy. The base class cancels
    // its coroutineScope during onDestroy(), which terminates any in-flight handler
    // coroutines cleanly before super returns.

    /**
     * Capture the BLE companion's UID so the UI knows whether watch
     * dictation goes through Android's SpeechRecognizer (microPebble →
     * Whisper can take over) or through Rebble's cloud (rePebble → it
     * can't). The bind callsite is the only place we have caller
     * identity from a third-party process — `Binder.getCallingUid()`
     * in `onMessageReceived` is too late (we're on a coroutine thread
     * by then, calling identity cleared).
     */
    override fun onBind(intent: AndroidIntent?): IBinder? {
        val callerUid = Binder.getCallingUid()
        val app = application as? WristotleApplication
        app?.pebbleCompanionDetector?.recordBinderUid(callerUid)
        return super.onBind(intent)
    }

    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        Log.d(TAG, "Message received from $watchappUUID: $data")
        if (watchappUUID != AppConstants.PEBBLE_UUID) return ReceiveResult.Ack

        // Under microPebble fan-out, each watch→phone message is delivered to both
        // PKJS and this companion in parallel. The watch tags routed traffic with
        // MSG_TARGET so the unintended side can drop it. Untagged messages (ping,
        // ready, settings) are companion- or pkjs-specific by key and bypass this.
        val target = data.int32(MessageKeys.MSG_TARGET)
        if (target != null && target != MessageKeys.TARGET_COMPANION) {
            Log.d(TAG, "Ignoring message — msg_target=$target (not for companion)")
            return ReceiveResult.Ack
        }

        if (data[MessageKeys.COMPANION_PING] != null) {
            Log.d(TAG, "Received COMPANION_PING, sending READY")
            transport.sendReady()
            return ReceiveResult.Ack
        }

        // Settings snapshot from the watch — ingest then we're done.
        // Any tuple keyed by one of the 10 setting keys signals "this is a
        // settings update," no marker key needed (the response to our
        // REQUEST_SETTINGS is the only realistic source of these, but the
        // detection logic is intentionally source-agnostic).
        if (watchSettingsRepository.isSettingsMessage(data)) {
            watchSettingsRepository.ingest(data)
            return ReceiveResult.Ack
        }

        // Fire-and-forget log of a watch-local command (time/battery/find_phone/etc).
        // No NLU needed — the watch already executed locally; we just persist the
        // exchange so it appears in the Conversation screen.
        val logQuery = data.text(MessageKeys.LOG_QUERY)
        if (logQuery != null) {
            val logResponse = data.text(MessageKeys.LOG_RESPONSE).orEmpty()
            val logHandler = data.text(MessageKeys.LOG_HANDLER) ?: "unknown"
            Log.d(TAG, "Watch-local log: handler=$logHandler query=$logQuery")
            logInteraction(
                query = logQuery,
                response = logResponse,
                handler = logHandler,
                requiresCompanion = false,
            )
            return ReceiveResult.Ack
        }

        // Unified voice-query path. Find which channel the watch sent it on so
        // we can route the response back the same way (back-compat with old
        // watch firmware that distinguishes reminder/cancel/companion).
        val reminderText = data.text(MessageKeys.REMINDER_QUERY)
        val cancelText = data.text(MessageKeys.CANCEL_QUERY)
        val companionText = data.text(MessageKeys.COMPANION_QUERY)
        val watchHint: Intent? = when {
            reminderText != null -> Intent.Reminder
            cancelText != null -> Intent.Cancel
            else -> null
        }
        val query = reminderText ?: cancelText ?: companionText ?: return ReceiveResult.Ack

        Log.d(TAG, "Classifying query: $query (watchHint=$watchHint)")
        val classified = runCatching { intentClassifier.classify(query) }
            .onFailure { Log.w(TAG, "classify failed", it) }
            .getOrNull()

        val routed = resolveIntent(classified, watchHint, query)
        Log.d(TAG, "Routed to intent=${routed.intent} confidence=${routed.confidence}")

        val dispatchResult = registry.dispatch(routed)
        Log.d(TAG, "Sending response: ${dispatchResult.response}")

        // Reply over the matching legacy channel so old watch firmware that
        // distinguishes reminder/cancel inboxes still routes the response right.
        when (watchHint) {
            Intent.Reminder -> transport.sendReminderResult(dispatchResult.response)
            Intent.Cancel -> transport.sendCancelResult(dispatchResult.response)
            else -> transport.sendResponse(dispatchResult.response)
        }

        if (dispatchResult.success) {
            // Fire-and-forget learning: doesn't block the response, doesn't
            // surface to the user. NluSettings gates whether anything sticks.
            coroutineScope.launch { learningCollector.record(query, routed.intent) }
        }

        // Claim and clear the audio path published by WhisperRecognizer for
        // this dictation, so the next session can publish a fresh one.
        val audioPath = (application as WristotleApplication).lastCapturedAudioPath
        (application as WristotleApplication).lastCapturedAudioPath = null

        logInteraction(
            query = query,
            response = dispatchResult.response,
            handler = dispatchResult.handler,
            requiresCompanion = true,
            success = dispatchResult.success,
            nluIntent = classified?.intent?.name,
            nluConfidence = classified?.confidence,
            audioFilePath = audioPath,
        )

        return ReceiveResult.Ack
    }

    /**
     * Pick the intent to actually dispatch on. Watch-hinted queries always
     * win (preserves today's behaviour even if the classifier disagrees).
     * For unhinted queries, apply the confidence + margin thresholds — sub-
     * threshold predictions become [Intent.Unknown]. Always populates slots
     * for the chosen intent via [slotExtractors].
     */
    private suspend fun resolveIntent(
        classified: IntentResult?,
        watchHint: Intent?,
        query: String,
    ): IntentResult {
        if (watchHint != null) {
            val slots = slotExtractors.extract(watchHint, query)
            return classified?.copy(intent = watchHint, slots = slots)
                ?: IntentResult(
                    intent = watchHint,
                    slots = slots,
                    confidence = 1f,
                    alternates = emptyList(),
                    rawQuery = query,
                )
        }
        if (classified == null) {
            return IntentResult(
                intent = Intent.Unknown,
                slots = emptyMap(),
                confidence = 0f,
                alternates = emptyList(),
                rawQuery = query,
            )
        }
        // Confidence + margin gate. Three paths:
        //   1. Above threshold AND margin clear of the runner-up: trust
        //      the classifier's pick directly.
        //   2. Above threshold but tight margin OR below threshold:
        //      check for an unambiguous opening verb (`text`/`call`/
        //      `remind`/`cancel`/`find phone`). If the prefix hint
        //      resolves cleanly, route to that — a verb at the front
        //      of the query is deterministic evidence the embedder
        //      may have missed (e.g. when a long body dilutes the
        //      cosine to the canonical intent centroid below 0.55).
        //   3. No hint AND no usable classifier pick → Unknown.
        val runnerUp = classified.alternates.firstOrNull()?.score ?: 0f
        val below = classified.confidence < NluSettings.ROUTE_THRESHOLD
        val ambiguous = !below && (classified.confidence - runnerUp) < NluSettings.ROUTE_MARGIN
        if (below || ambiguous) {
            val why = if (below) "below-threshold" else "ambiguous"
            val hint = PrefixHints.hintFor(query)
            if (hint != null) {
                Log.d(TAG, "$why (conf=${classified.confidence} runnerUp=$runnerUp) → prefix hint $hint wins")
                val slots = slotExtractors.extract(hint, query)
                return classified.copy(intent = hint, slots = slots)
            }
            Log.d(TAG, "$why (conf=${classified.confidence} runnerUp=$runnerUp) and no prefix hint → Unknown")
            return classified.copy(intent = Intent.Unknown, slots = emptyMap())
        }
        val slots = slotExtractors.extract(classified.intent, query)
        return classified.copy(slots = slots)
    }

    /**
     * Persists one interaction into the conversation history. Called for every
     * dispatch path (reminder/cancel/registry) and for watch-local logs.
     * Success heuristic comes from [HandlerRegistry.isSuccessResponse].
     */
    private fun logInteraction(
        query: String,
        response: String,
        handler: String,
        requiresCompanion: Boolean,
        success: Boolean = isSuccessResponse(response),
        nluIntent: String? = null,
        nluConfidence: Float? = null,
        audioFilePath: String? = null,
    ) {
        val entry = ConversationEntry(
            timestampEpochMs = System.currentTimeMillis(),
            userQuery = query,
            responseText = response,
            handler = handler,
            requiresCompanion = requiresCompanion,
            success = success,
            nluIntent = nluIntent,
            nluConfidence = nluConfidence,
            audioFilePath = audioFilePath,
        )
        coroutineScope.launch {
            runCatching { conversationRepository.add(entry) }
                .onFailure { Log.w(TAG, "Failed to persist conversation entry", it) }
        }
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        Log.d(TAG, "App opened: $watchappUUID")
        if (watchappUUID == AppConstants.PEBBLE_UUID) {
            coroutineScope.launch { transport.sendReady() }
        }
    }

    companion object {
        private const val TAG = "PebbleListenerService"
    }
}
