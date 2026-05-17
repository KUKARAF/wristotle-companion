package com.lazydevs.wristotle.service

import android.util.Log
import com.lazydevs.wristotle.AppConstants
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.handlers.CallHandler
import com.lazydevs.wristotle.handlers.CancelReminderHandler
import com.lazydevs.wristotle.handlers.HandlerRegistry
import com.lazydevs.wristotle.handlers.HandlerRegistry.Companion.isSuccessResponse
import com.lazydevs.wristotle.handlers.ReminderHandler
import com.lazydevs.wristotle.handlers.SmsHandler
import com.lazydevs.wristotle.history.ConversationEntry
import com.lazydevs.wristotle.history.ConversationRepository
import com.lazydevs.wristotle.phone.ContactsRepository
import com.lazydevs.wristotle.speech.nlu.IntentClassifier
import com.lazydevs.wristotle.speech.nlu.IntentClassifiers
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

/**
 * Receives AppMessages from the Pebble watch via rePebble and dispatches them to handlers.
 *
 * Registered in the manifest with the `io.rebble.pebblekit2.RECEIVE_DATA_FROM_WATCH` intent
 * filter so rePebble binds to it when a message arrives. Note: received integer values are
 * always delivered as UInt32/Int32 regardless of their original size on the watch.
 */
class PebbleListenerService : BasePebbleListenerService() {

    private lateinit var transport: PebbleTransport
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var registry: HandlerRegistry
    private lateinit var reminderHandler: ReminderHandler
    private lateinit var cancelHandler: CancelReminderHandler
    private lateinit var intentClassifier: IntentClassifier

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service bound by rePebble")
        val app = application as WristotleApplication
        transport = app.transport
        conversationRepository = app.conversationRepository
        intentClassifier = IntentClassifiers.provider(this)
        val contacts = ContactsRepository(this)
        registry = HandlerRegistry(listOf(
            CallHandler(this, contacts),
            SmsHandler(this, contacts),
        ))
        reminderHandler = ReminderHandler(this, transport)
        cancelHandler = CancelReminderHandler(this, transport)
    }

    // Transport is Application-owned; no close in onDestroy. The base class cancels
    // its coroutineScope during onDestroy(), which terminates any in-flight handler
    // coroutines cleanly before super returns.

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

        // Fire-and-forget log of a watch-local command (time/battery/find_phone/etc).
        // No response needed — the watch has already shown its result; we just persist
        // it so it appears in the Conversation screen.
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

        val reminderQuery = data.text(MessageKeys.REMINDER_QUERY)
        if (reminderQuery != null) {
            Log.d(TAG, "Reminder query: $reminderQuery")
            val result = reminderHandler.handle(reminderQuery)
            transport.sendReminderResult(result)
            logInteractionWithNlu(reminderQuery, result, handler = "reminder", requiresCompanion = true)
            return ReceiveResult.Ack
        }

        val cancelQuery = data.text(MessageKeys.CANCEL_QUERY)
        if (cancelQuery != null) {
            Log.d(TAG, "Cancel query: $cancelQuery")
            val result = cancelHandler.handle(cancelQuery)
            transport.sendCancelResult(result)
            logInteractionWithNlu(cancelQuery, result, handler = "cancel", requiresCompanion = true)
            return ReceiveResult.Ack
        }

        val query = data.text(MessageKeys.COMPANION_QUERY)
            ?: return ReceiveResult.Ack

        Log.d(TAG, "Dispatching query: $query")
        val dispatchResult = registry.dispatch(query)
        Log.d(TAG, "Sending response: ${dispatchResult.response}")
        transport.sendResponse(dispatchResult.response)
        logInteractionWithNlu(
            query,
            dispatchResult.response,
            handler = dispatchResult.handler,
            requiresCompanion = true,
            success = dispatchResult.success,
        )

        return ReceiveResult.Ack
    }

    /**
     * Wraps [logInteraction] with a shadow-mode NLU classification: runs the
     * classifier on the query, records its predicted intent + confidence on
     * the row, but doesn't act on the prediction. Phase 3 will let the
     * prediction actually drive dispatch.
     *
     * Failures in the classifier never block the user-visible response —
     * the prediction is best-effort, the row is persisted either way.
     */
    private fun logInteractionWithNlu(
        query: String,
        response: String,
        handler: String,
        requiresCompanion: Boolean,
        success: Boolean = isSuccessResponse(response),
    ) {
        coroutineScope.launch {
            val prediction = runCatching { intentClassifier.classify(query) }
                .onFailure { Log.w(TAG, "NLU classify failed", it) }
                .getOrNull()
            val entry = ConversationEntry(
                timestampEpochMs = System.currentTimeMillis(),
                userQuery = query,
                responseText = response,
                handler = handler,
                requiresCompanion = requiresCompanion,
                success = success,
                nluIntent = prediction?.intent?.name,
                nluConfidence = prediction?.confidence,
            )
            runCatching { conversationRepository.add(entry) }
                .onFailure { Log.w(TAG, "Failed to persist conversation entry", it) }
        }
    }

    /**
     * Persists one interaction into the conversation history. Called for every
     * dispatch path (reminder/cancel/registry). Reminder + cancel use the heuristic
     * success classifier from [HandlerRegistry] since they don't return rich results.
     */
    private fun logInteraction(
        query: String,
        response: String,
        handler: String,
        requiresCompanion: Boolean,
        success: Boolean = isSuccessResponse(response),
    ) {
        val entry = ConversationEntry(
            timestampEpochMs = System.currentTimeMillis(),
            userQuery = query,
            responseText = response,
            handler = handler,
            requiresCompanion = requiresCompanion,
            success = success,
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
