// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.service

import com.lazydevs.wristotle.logging.WristotleLog as Log
import com.lazydevs.wristotle.AppConstants
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.speech.nlu.slots.SlotKeys
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.handlers.CalendarHandler
import com.lazydevs.wristotle.handlers.CallHandler
import com.lazydevs.wristotle.handlers.CancelReminderHandler
import com.lazydevs.wristotle.handlers.CreateEventHandler
import com.lazydevs.wristotle.handlers.FindPhoneHandler
import com.lazydevs.wristotle.handlers.ConfirmSummaryBuilder
import com.lazydevs.wristotle.handlers.HandlerRegistry
import com.lazydevs.wristotle.handlers.HandlerRegistry.Companion.isSuccessResponse
import com.lazydevs.wristotle.handlers.requiresConfirm
import com.lazydevs.wristotle.handlers.ListRemindersHandler
import com.lazydevs.wristotle.handlers.MediaNextHandler
import com.lazydevs.wristotle.handlers.MediaPauseHandler
import com.lazydevs.wristotle.handlers.MediaPlayHandler
import com.lazydevs.wristotle.handlers.MediaPlayPauseHandler
import com.lazydevs.wristotle.handlers.MediaPreviousHandler
import com.lazydevs.wristotle.handlers.MediaSeekHandler
import com.lazydevs.wristotle.handlers.AppendNoteHandler
import com.lazydevs.wristotle.handlers.NoteHandler
import com.lazydevs.wristotle.handlers.OpenAppHandler
import com.lazydevs.wristotle.handlers.ReminderHandler
import com.lazydevs.wristotle.handlers.CancelAlarmHandler
import com.lazydevs.wristotle.handlers.SetTimerHandler
import com.lazydevs.wristotle.handlers.RescheduleHandler
import com.lazydevs.wristotle.history.ConversationEntry
import com.lazydevs.wristotle.history.ConversationRepository
import com.lazydevs.wristotle.nlu.LearningCollector
import com.lazydevs.wristotle.nlu.NluSettings
import com.lazydevs.wristotle.speech.nlu.VoicePipeline
import com.lazydevs.wristotle.phone.CalendarRepository
import com.lazydevs.wristotle.phone.ContactsRepository
import com.lazydevs.wristotle.settings.WatchSettingsRepository
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentClassifiers
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractorRegistry
import com.lazydevs.wristotle.transport.MessageKeys
import com.lazydevs.wristotle.transport.PebbleTransport
import com.lazydevs.wristotle.transport.boolFlag
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

    /** The application singleton, cached once at [onCreate] so the
     *  ad-hoc `application as WristotleApplication` casts that used to
     *  live inside [onMessageReceived] don't fire on every inbound
     *  packet. Use this to reach any Application-scoped repository. */
    private lateinit var app: WristotleApplication
    private lateinit var transport: PebbleTransport
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var registry: HandlerRegistry
    private lateinit var voicePipeline: VoicePipeline
    private lateinit var nluSettings: NluSettings
    private lateinit var learningCollector: LearningCollector
    private lateinit var watchSettingsRepository: WatchSettingsRepository
    /** Single per-service ContactsRepository — used both at handler-
     *  registration time (passed into Call / SendMessage handlers) and
     *  in [enrichResolvedContact]. Held as a field so the enrich-pass
     *  doesn't construct a fresh repository per Call / SendMessage
     *  query and lose the alias-store + lookup caches it has built. */
    private lateinit var contacts: ContactsRepository

    /** Handles the four watch-initiated request/response pairs that aren't
     *  voice queries (notes / note-detail / tasks / task-complete). Built
     *  once at [onCreate]; routes via [WatchRequestRouter.tryHandle] from
     *  the head of [onMessageReceived]. */
    private lateinit var watchRequests: WatchRequestRouter

    /** A query the watch requested a confirm prompt for, stashed between
     *  the outbound CONFIRM_PROMPT and the inbound CONFIRM_RESPONSE. Single
     *  in-flight by design — watch dictation is sequential, the user can't
     *  start a new one while the confirm window is up. A second confirm-
     *  eligible query arriving while one's pending overwrites the stash
     *  (and the old stash's audio file is logged + dropped — see
     *  [stashPendingConfirm]); the stale CONFIRM_RESPONSE then no-ops
     *  because the routed intent it was going to dispatch is gone.
     *
     *  Held in an [AtomicReference] so the read-then-clear in
     *  [handleConfirmResponse] is a true atomic swap. PK2's callback
     *  dispatcher serialises today, but the contract is "could fire
     *  concurrently" and we shouldn't rely on coincidence. */
    private data class PendingConfirm(
        val routed: com.lazydevs.wristotle.speech.nlu.IntentResult,
        val query: String,
        val watchHint: Intent?,
        val audioPath: String?,
        val classified: com.lazydevs.wristotle.speech.nlu.IntentResult?,
    )
    private val pendingConfirm = java.util.concurrent.atomic.AtomicReference<PendingConfirm?>(null)

    override fun onCreate() {
        super.onCreate()
        // Note: the actual BLE companion (rePebble vs microPebble) is
        // captured in onBind via Binder.getCallingUid; see the override
        // below + PebbleCompanionDetector.
        Log.d(TAG, "PebbleListenerService onCreate")
        app = application as WristotleApplication
        transport = app.transport
        conversationRepository = app.conversationRepository
        voicePipeline = VoicePipeline(
            classifier = IntentClassifiers.provider(this),
            slotExtractors = app.slotExtractors,
            askAgentSubjects = { app.askAgentSettings.customTriggers.value },
            // R3 batch 1 — pipe the Android-side ring-buffered logger
            // through the multiplatform Logger interface.
            logger = com.lazydevs.wristotle.logging.WristotleLogger,
        )
        nluSettings = app.nluSettings
        learningCollector = app.learningCollector
        watchSettingsRepository = app.watchSettingsRepository

        contacts = ContactsRepository(this)
        watchRequests = WatchRequestRouter(app, transport)
        val media = app.activeMediaSession
        val appIndex = app.appIndex
        val calendarRepo = CalendarRepository(this)
        registry = HandlerRegistry(listOf(
            CallHandler(this, contacts),
            com.lazydevs.wristotle.handlers.SendMessageHandler(this, contacts),
            ReminderHandler(
                this,
                transport,
                app.persistentReminderScheduler,
                defaultMaxAttemptsProvider = { app.reminderSettings.defaultMaxAttempts.value },
            ),
            CancelReminderHandler(this, transport, app.persistentReminderScheduler),
            ListRemindersHandler(this),
            RescheduleHandler(this, transport, app.persistentReminderScheduler),
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
            NoteHandler(app.noteRepository),
            AppendNoteHandler(app.noteRepository),
            com.lazydevs.wristotle.handlers.AddTaskHandler(app.taskRepository),
            com.lazydevs.wristotle.handlers.ListTasksHandler(app.taskRepository),
            com.lazydevs.wristotle.handlers.CompleteTaskHandler(app.taskRepository),
            com.lazydevs.wristotle.handlers.DeleteTaskHandler(app.taskRepository),
            CancelAlarmHandler(
                transport = app.transport,
                repository = app.alarmRepository,
                dispatcher = app.alarmDispatcher,
            ),
            com.lazydevs.wristotle.handlers.SetAlarmHandler(
                repository = app.alarmRepository,
                dispatcher = app.alarmDispatcher,
                settings = app.alarmSettings,
            ),
            SetTimerHandler(this),
            com.lazydevs.wristotle.handlers.WorldTimeHandler(),
            com.lazydevs.wristotle.handlers.CalculateHandler(),
            com.lazydevs.wristotle.handlers.WeatherHandler(
                openMeteo = com.lazydevs.wristotle.handlers.OpenMeteoProvider(),
                openWeatherFactory = { key -> com.lazydevs.wristotle.handlers.OpenWeatherProvider(key) },
                phoneLocation = com.lazydevs.wristotle.phone.PhoneLocation(this),
                settings = app.weatherSettings,
            ),
            com.lazydevs.wristotle.handlers.AskAgentHandler(
                settings = app.askAgentSettings,
                mcpServers = app.mcpServerRepository,
                transport = app.transport,
            ),
            com.lazydevs.wristotle.handlers.MorningBriefHandler(
                context = this,
                calendar = calendarRepo,
                alarms = app.alarmRepository,
                tasks = app.taskRepository,
                notes = app.noteRepository,
                unreadMessages = com.lazydevs.wristotle.briefing.UnreadMessagesProvider(
                    postsDao = app.notificationLogDb.notificationPostDao(),
                ),
                notifLogEnabledProvider = { app.notificationLogSettings.enabled.value },
            ),
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
        // Defensive cast — onBind can fire before onCreate completes, so
        // the [app] lateinit field may not be ready yet.
        (application as? WristotleApplication)
            ?.pebbleCompanionDetector
            ?.recordBinderUid(callerUid)
        return super.onBind(intent)
    }

    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
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

        // Log AFTER the UUID + MSG_TARGET guards so we don't iterate the
        // dictionary's toString() for the fan-out half of every microPebble
        // message we're about to drop.
        Log.d(TAG, "Message received from $watchappUUID: $data")

        if (data[MessageKeys.COMPANION_PING] != null) {
            Log.d(TAG, "Received COMPANION_PING, sending READY")
            transport.sendReady()
            return ReceiveResult.Ack
        }

        // Watch-initiated request/response pairs (notes / note-detail / tasks /
        // task-complete). All four follow the same shape and are handled by
        // [WatchRequestRouter] so the voice-query path below can stay focused.
        if (watchRequests.tryHandle(data)) return ReceiveResult.Ack

        // Confirm-before-dispatch reply from the watch. Placed before the
        // settings/log/query branches because CONFIRM_RESPONSE carries no
        // query text — it would silently fall through to "no query → Ack"
        // otherwise. Consume the stash and either dispatch (decision == 1)
        // or surface a "Cancelled." response back to the watch.
        val confirmDecision = data.int32(MessageKeys.CONFIRM_RESPONSE)
        if (confirmDecision != null) {
            handleConfirmResponse(confirmed = confirmDecision == 1)
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

        // Phase A2 of confirm-before-dispatch: the watch attaches this flag
        // when the user has the Confirm-action toggle on. Just observed here;
        // Phase A3 will actually intercept destructive intents on a true flag.
        val confirmRequested = data.boolFlag(MessageKeys.SETTING_CONFIRM_BEFORE_SEND)
        Log.d(TAG, "Classifying query: $query (watchHint=$watchHint confirmRequested=$confirmRequested)")
        val (routedRaw, classified) = voicePipeline.route(query, watchHint)
        // Enrich Call / SendMessage with the resolved contact's display
        // name from ContactsRepository. The slot extractors only keep
        // the spoken candidate (e.g. "mom"); the confirm prompt needs
        // to show what's actually about to be dialled / texted (e.g.
        // "Mom Smith") so the user catches a mis-resolution before
        // SELECT. Idempotent: handlers re-resolve at dispatch time.
        val routed = enrichResolvedContact(routedRaw)
        Log.d(TAG, "Routed to intent=${routed.intent} confidence=${routed.confidence}")

        val (routedWithAudio, audioPath) = claimAudioPath(routed)

        // Confirm gate: when the user has the Watch toggle on AND the routed
        // intent is destructive, stash the dispatch state + ship a confirm
        // prompt back. The actual dispatch runs when CONFIRM_RESPONSE arrives
        // (see the branch added above onMessageReceived).
        if (confirmRequested == true && routedWithAudio.intent.requiresConfirm()) {
            val summary = ConfirmSummaryBuilder.summary(routedWithAudio)
            stashPendingConfirm(
                PendingConfirm(
                    routed = routedWithAudio,
                    query = query,
                    watchHint = watchHint,
                    audioPath = audioPath,
                    classified = classified,
                ),
                summary = summary,
            )
            transport.sendConfirmPrompt(summary)
            return ReceiveResult.Ack
        }

        dispatchAndReport(
            routed = routedWithAudio,
            query = query,
            watchHint = watchHint,
            audioPath = audioPath,
            classified = classified,
        )
        return ReceiveResult.Ack
    }

    /**
     * Runs an already-resolved [routed] action and reports the result back to
     * the watch + persists a [ConversationEntry]. Extracted out of the
     * COMPANION_QUERY branch so both the immediate path and the post-confirm
     * path call the same code.
     */
    private suspend fun dispatchAndReport(
        routed: com.lazydevs.wristotle.speech.nlu.IntentResult,
        query: String,
        watchHint: Intent?,
        audioPath: String?,
        classified: com.lazydevs.wristotle.speech.nlu.IntentResult?,
    ) {
        // Catch the "user dictated a natural-language command (calendar,
        // paraphrased reminder, …) but no NLU model is loaded" case before
        // the registry returns the unhelpful "Unknown command: <query>" line.
        // The stub classifier returns Unknown for everything, so detecting
        // isStub + Unknown intent + no watch-hint to override is the
        // unambiguous signal we're in this state. Give the user something
        // actionable instead of silent failure.
        val noNluModel = routed.intent == Intent.Unknown &&
            watchHint == null &&
            voicePipeline.isStubClassifier
        val dispatchResult = if (noNluModel) {
            com.lazydevs.wristotle.handlers.HandlerResult(
                response = getString(R.string.nlu_model_missing_response),
                handler = "no-nlu-model",
                success = false,
            )
        } else {
            registry.dispatch(routed)
        }
        Log.d(TAG, "Sending response: ${dispatchResult.response}")

        transport.sendForHint(watchHint, dispatchResult.response)

        if (dispatchResult.success) {
            // Fire-and-forget learning: doesn't block the response, doesn't
            // surface to the user. NluSettings gates whether anything sticks.
            coroutineScope.launch { learningCollector.record(query, routed.intent) }
        }

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
    }

    /**
     * Atomically replace the in-flight [PendingConfirm] stash with a new
     * one. When a stash already existed (re-entrant confirm — rare, but
     * possible when the user starts a new dictation while a prompt is
     * up), warn and delete the orphaned audio file. Without this the
     * prior dictation's `.wav` would stay around forever on disk with
     * no row in conversation-audio/ pointing at it.
     */
    private fun stashPendingConfirm(next: PendingConfirm, summary: String) {
        val prior = pendingConfirm.getAndSet(next)
        if (prior != null) {
            Log.w(TAG, "Confirm overwrite: previous intent=${prior.routed.intent} replaced before user responded")
            prior.audioPath?.let { path ->
                runCatching { java.io.File(path).takeIf(java.io.File::exists)?.delete() }
                    .onFailure { Log.w(TAG, "Failed to delete orphaned audio $path", it) }
            }
        }
        Log.d(TAG, "Confirm gating: stash intent=${next.routed.intent}, send prompt:\n$summary")
    }

    /**
     * Consumes a [PendingConfirm] stash on inbound CONFIRM_RESPONSE. When
     * the user confirmed (1), runs the stashed dispatch via the shared
     * [dispatchAndReport] helper. When they cancelled (0 — or BACK / timeout
     * on the watch UI in Phase B), sends a "Cancelled." reply back over the
     * matching channel and logs a `cancelled` ConversationEntry so the
     * action appears in history (success=false, learning skipped).
     */
    private suspend fun handleConfirmResponse(confirmed: Boolean) {
        val pending = pendingConfirm.getAndSet(null)
        if (pending == null) {
            Log.w(TAG, "CONFIRM_RESPONSE arrived with no pending confirm — ignoring")
            return
        }
        if (confirmed) {
            Log.d(TAG, "Confirm: dispatching stashed intent=${pending.routed.intent}")
            dispatchAndReport(
                routed = pending.routed,
                query = pending.query,
                watchHint = pending.watchHint,
                audioPath = pending.audioPath,
                classified = pending.classified,
            )
        } else {
            Log.d(TAG, "Confirm: user cancelled intent=${pending.routed.intent}")
            val cancelMsg = getString(R.string.confirm_cancelled_reply)
            transport.sendForHint(pending.watchHint, cancelMsg)
            logCancelled(pending, cancelMsg)
        }
    }

    /**
     * Resolve the spoken contact name (slots["contact"]) to the actual
     * contact's display name via [ContactsRepository], stashed as
     * `slots["resolvedContact"]`. Only enriches Call + SendMessage —
     * the only intents whose confirm prompt currently shows a contact.
     *
     * Idempotent: handlers re-resolve the contact at dispatch time via
     * the same `findContact` call, so this enrichment is purely for
     * the confirm prompt's display. If `findContact` returns null (the
     * spoken name doesn't match any contact), no enrichment happens —
     * the prompt falls back to the spoken value and the dispatch will
     * fail honestly with *"Contact not found"*.
     *
     * Short-circuits when the slot extractor already stashed a
     * [ContactsRepository.Contact] (SendMessage's multi-word loop
     * resolves the contact during extraction). For Call, CallSlots
     * doesn't pre-resolve, so this still does the lookup.
     */
    private suspend fun enrichResolvedContact(routed: IntentResult): IntentResult {
        if (routed.intent != Intent.Call && routed.intent != Intent.SendMessage) return routed
        if (routed.slots[SlotKeys.ResolvedContact] is ContactsRepository.Contact) return routed
        val spoken = (routed.slots[SlotKeys.Contact] as? String)?.trim().orEmpty()
        if (spoken.isEmpty()) return routed
        if (!contacts.hasPermission()) return routed
        val match = contacts.findContact(spoken) ?: return routed
        return routed.copy(slots = routed.slots + (SlotKeys.ResolvedContact to match))
    }

    /**
     * Claim the recognizer's published .wav path now (before dispatch /
     * confirm-gate) so the NoteHandler can copy it into permanent
     * notes-audio/. The path is also recorded on the ConversationEntry —
     * claiming once and reusing keeps watch-dictation audio attached to
     * BOTH the history row and (when the intent is Note) the saved note
     * row. When confirm-gated, the path travels with the PendingConfirm
     * stash so it survives the prompt round-trip.
     *
     * Returns the (possibly-rewritten) intent result + the raw path; the
     * caller plumbs `audioPath` separately so the ConversationEntry gets
     * it even for non-Note intents.
     */
    private fun claimAudioPath(routed: IntentResult): Pair<IntentResult, String?> {
        val audioPath = app.lastCapturedAudioPath
        app.lastCapturedAudioPath = null
        val withAudio = if (
            (routed.intent == Intent.Note || routed.intent == Intent.AppendNote)
            && audioPath != null
        ) {
            routed.copy(slots = routed.slots + (SlotKeys.AudioPath to audioPath))
        } else routed
        return withAudio to audioPath
    }

    /**
     * Convenience wrapper for the confirm-cancelled branch — passes the
     * stashed query / audio / classifier through to [logInteraction] with
     * the canonical `"cancelled"` handler tag and `success = false`.
     */
    private fun logCancelled(pending: PendingConfirm, response: String) {
        logInteraction(
            query = pending.query,
            response = response,
            handler = HANDLER_CANCELLED,
            requiresCompanion = true,
            success = false,
            nluIntent = pending.classified?.intent?.name,
            nluConfidence = pending.classified?.confidence,
            audioFilePath = pending.audioPath,
        )
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

        /** Persisted in [ConversationEntry.handler] for the cancelled-
         *  confirm path; surfaced verbatim on the Conversation screen. */
        private const val HANDLER_CANCELLED = "cancelled"
    }
}