package com.lazydevs.wristotle.service

import com.lazydevs.wristotle.logging.WristotleLog as Log
import com.lazydevs.wristotle.AppConstants
import com.lazydevs.wristotle.R
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
import com.lazydevs.wristotle.handlers.SetAlarmHandler
import com.lazydevs.wristotle.handlers.SetTimerHandler
import com.lazydevs.wristotle.handlers.RescheduleHandler
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

    private lateinit var transport: PebbleTransport
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var registry: HandlerRegistry
    private lateinit var intentClassifier: IntentClassifier
    private lateinit var slotExtractors: SlotExtractorRegistry
    private lateinit var nluSettings: NluSettings
    private lateinit var learningCollector: LearningCollector
    private lateinit var watchSettingsRepository: WatchSettingsRepository

    /** Last batch of notes shipped to the watch via NOTES_RESPONSE. The watch
     *  refers back by zero-based index when it wants a single note's full body
     *  via NOTE_DETAIL_REQUEST — short-lived: each NOTES_REQUEST replaces it.
     *  @Volatile so the IO write from one onMessageReceived is visible to
     *  any subsequent dispatch on a different thread. */
    @Volatile private var lastNotesSnapshot: List<com.lazydevs.wristotle.notes.Note> = emptyList()

    /** A query the watch requested a confirm prompt for, stashed between
     *  the outbound CONFIRM_PROMPT and the inbound CONFIRM_RESPONSE. Single
     *  in-flight by design — watch dictation is sequential, the user can't
     *  start a new one while the confirm window is up. A second confirm-
     *  eligible query arriving while one's pending overwrites the stash;
     *  the stale CONFIRM_RESPONSE then no-ops because the routed intent it
     *  was going to dispatch is gone. */
    private data class PendingConfirm(
        val routed: com.lazydevs.wristotle.speech.nlu.IntentResult,
        val query: String,
        val watchHint: Intent?,
        val audioPath: String?,
        val classified: com.lazydevs.wristotle.speech.nlu.IntentResult?,
    )
    @Volatile private var pendingConfirm: PendingConfirm? = null

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
            com.lazydevs.wristotle.handlers.SendMessageHandler(this, contacts),
            ReminderHandler(this, transport),
            CancelReminderHandler(this, transport),
            ListRemindersHandler(this),
            RescheduleHandler(this, transport),
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
            SetAlarmHandler(this),
            SetTimerHandler(this),
            com.lazydevs.wristotle.handlers.WorldTimeHandler(),
            com.lazydevs.wristotle.handlers.CalculateHandler(),
            com.lazydevs.wristotle.handlers.WeatherHandler(
                openMeteo = com.lazydevs.wristotle.handlers.OpenMeteoProvider(),
                openWeatherFactory = { key -> com.lazydevs.wristotle.handlers.OpenWeatherProvider(key) },
                phoneLocation = com.lazydevs.wristotle.phone.PhoneLocation(this),
                settings = app.weatherSettings,
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

        // Notes-on-watch request: presence-only key. Ship a snapshot of the
        // most recent notes back over NOTES_RESPONSE, and remember the
        // ordering so a follow-up NOTE_DETAIL_REQUEST(index) resolves to
        // the matching note id.
        if (data[MessageKeys.NOTES_REQUEST] != null) {
            val notesApp = application as WristotleApplication
            val notes = notesApp.noteRepository.mostRecent(
                com.lazydevs.wristotle.notes.NotesResponseFormatter.MAX_NOTES,
            )
            lastNotesSnapshot = notes
            val payload = com.lazydevs.wristotle.notes.NotesResponseFormatter.format(notes)
            Log.d(TAG, "NOTES_REQUEST → sending ${notes.size} notes (${payload.length} chars)")
            transport.sendNotesResponse(payload)
            return ReceiveResult.Ack
        }

        // Tasks-on-watch request: Int32 filter (0=pending, 1=completed,
        // 2=all). Default to pending when the value is missing OR
        // unrecognised so older watch builds still get a sensible
        // response.
        if (data[MessageKeys.TASKS_REQUEST] != null) {
            val tasksApp = application as WristotleApplication
            val filter = data.int32(MessageKeys.TASKS_REQUEST) ?: MessageKeys.TASKS_FILTER_PENDING
            val list = when (filter) {
                MessageKeys.TASKS_FILTER_COMPLETED -> tasksApp.taskRepository.listCompleted()
                MessageKeys.TASKS_FILTER_ALL       -> tasksApp.taskRepository.listAll()
                else                               -> tasksApp.taskRepository.listPending()
            }
            val payload = com.lazydevs.wristotle.tasks.TasksWireFrame.encode(list)
            Log.d(TAG, "TASKS_REQUEST filter=$filter → sending ${list.size} tasks (${payload.length} chars)")
            transport.sendTasksResponse(payload)
            return ReceiveResult.Ack
        }

        // Per-task quick-complete: int32 task id. Watch's SELECT on a
        // task row sends this; companion marks complete + replies with
        // a user-facing string the watch shows in the chat surface.
        val completeTaskId = data.int32(MessageKeys.TASK_COMPLETE_REQUEST)
        if (completeTaskId != null) {
            val tasksApp = application as WristotleApplication
            val task = tasksApp.taskRepository.findById(completeTaskId.toLong())
            val reply = when {
                task == null -> "Task no longer exists"
                task.completed -> "Task already completed"
                else -> {
                    tasksApp.taskRepository.markCompleted(task.id)
                    "Completed: ${task.text}"
                }
            }
            Log.d(TAG, "TASK_COMPLETE_REQUEST id=$completeTaskId → $reply")
            transport.sendTaskCompleteResponse(reply)
            return ReceiveResult.Ack
        }

        // Per-note detail fetch: int32 index into the most-recent snapshot.
        // Watch shows the full body in its detail window, with a short
        // timestamp header prepended for context.
        val detailIndex = data.int32(MessageKeys.NOTE_DETAIL_REQUEST)
        if (detailIndex != null) {
            val snapshot = lastNotesSnapshot
            val note = snapshot.getOrNull(detailIndex)
            val payload = if (note == null) "" else {
                val header = android.text.format.DateFormat
                    .format("MMM d, h:mm a", note.createdAtEpochMs).toString()
                // Reserve header + 2 newlines + ellipsis from the budget so the
                // truncation happens on the body, not on the timestamp.
                val budgetForBody = MessageKeys.NOTE_DETAIL_MAX_CHARS - header.length - 2
                val body = if (note.body.length > budgetForBody) {
                    note.body.substring(0, budgetForBody - 1) + "…"
                } else note.body
                "$header\n\n$body"
            }
            Log.d(TAG, "NOTE_DETAIL_REQUEST idx=$detailIndex → ${payload.length} chars")
            transport.sendNoteDetailResponse(payload)
            return ReceiveResult.Ack
        }

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
        val classified = runCatching { intentClassifier.classify(query) }
            .onFailure { Log.w(TAG, "classify failed", it) }
            .getOrNull()

        val routedRaw = resolveIntent(classified, watchHint, query)
        // Enrich Call / SendMessage with the resolved contact's display
        // name from ContactsRepository. The slot extractors only keep
        // the spoken candidate (e.g. "mom"); the confirm prompt needs
        // to show what's actually about to be dialled / texted (e.g.
        // "Mom Smith") so the user catches a mis-resolution before
        // SELECT. Idempotent: handlers re-resolve at dispatch time.
        val routed = enrichResolvedContact(routedRaw)
        Log.d(TAG, "Routed to intent=${routed.intent} confidence=${routed.confidence}")
        // Phase A1.5 of confirm-before-dispatch: log the parsed action + slots
        // for every query so we can verify what the eventual confirm prompt
        // would say before any wire protocol is in place. No behaviour change
        // — pure observability. Will be reused as the confirm prompt body in
        // Phase A3.
        Log.d(TAG, "Confirm preview:\n${ConfirmSummaryBuilder.summary(routed)}")

        // Claim the recognizer's published .wav path now (before dispatch /
        // confirm-gate) so the NoteHandler can copy it into permanent notes-
        // audio/. The same path is still recorded on the ConversationEntry —
        // claiming once and reusing keeps watch-dictation audio attached to
        // BOTH the history row and (when the intent is Note) the saved note
        // row. When confirm-gated, the path travels with the PendingConfirm
        // stash so it survives the prompt round-trip.
        val app2 = application as WristotleApplication
        val audioPath = app2.lastCapturedAudioPath
        app2.lastCapturedAudioPath = null
        val routedWithAudio = if (
            (routed.intent == Intent.Note || routed.intent == Intent.AppendNote)
            && audioPath != null
        ) {
            routed.copy(slots = routed.slots + (NoteHandler.SLOT_AUDIO_PATH to audioPath))
        } else routed

        // Confirm gate: when the user has the Watch toggle on AND the routed
        // intent is destructive, stash the dispatch state + ship a confirm
        // prompt back. The actual dispatch runs when CONFIRM_RESPONSE arrives
        // (see the branch added above onMessageReceived).
        if (confirmRequested == true && routedWithAudio.intent.requiresConfirm()) {
            val summary = ConfirmSummaryBuilder.summary(routedWithAudio)
            pendingConfirm = PendingConfirm(
                routed = routedWithAudio,
                query = query,
                watchHint = watchHint,
                audioPath = audioPath,
                classified = classified,
            )
            Log.d(TAG, "Confirm gating: stash intent=${routedWithAudio.intent}, send prompt:\n$summary")
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
        // it == "stub" + Unknown intent + no watch-hint to override is the
        // unambiguous signal we're in this state. Give the user something
        // actionable instead of silent failure.
        val noNluModel = routed.intent == Intent.Unknown &&
            watchHint == null &&
            intentClassifier.tag == "stub"
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
     * Consumes a [PendingConfirm] stash on inbound CONFIRM_RESPONSE. When
     * the user confirmed (1), runs the stashed dispatch via the shared
     * [dispatchAndReport] helper. When they cancelled (0 — or BACK / timeout
     * on the watch UI in Phase B), sends a "Cancelled." reply back over the
     * matching channel and logs a `cancelled` ConversationEntry so the
     * action appears in history (success=false, learning skipped).
     */
    private suspend fun handleConfirmResponse(confirmed: Boolean) {
        val pending = pendingConfirm
        pendingConfirm = null
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
            when (pending.watchHint) {
                Intent.Reminder -> transport.sendReminderResult(cancelMsg)
                Intent.Cancel -> transport.sendCancelResult(cancelMsg)
                else -> transport.sendResponse(cancelMsg)
            }
            logInteraction(
                query = pending.query,
                response = cancelMsg,
                handler = "cancelled",
                requiresCompanion = true,
                success = false,
                nluIntent = pending.classified?.intent?.name,
                nluConfidence = pending.classified?.confidence,
                audioFilePath = pending.audioPath,
            )
        }
    }

    /**
     * Pick the intent to actually dispatch on. Watch-hinted queries win
     * (preserves today's behaviour even if the classifier disagrees) — except
     * a `Reminder` hint may be refined *within the reminder family* (see below).
     * For unhinted queries, apply the confidence + margin thresholds — sub-
     * threshold predictions become [Intent.Unknown]. Always populates slots
     * for the chosen intent via [slotExtractors].
     */
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
     * Why pre-confirm instead of inside the slot extractor: CallSlots
     * doesn't currently take a `findContact` dep, and we don't want to
     * change every contact-using slot extractor's constructor signature
     * for a display-only concern. The lookup is cheap (one Contacts
     * content-provider query) so doing it once here costs nothing.
     */
    private suspend fun enrichResolvedContact(routed: IntentResult): IntentResult {
        if (routed.intent != Intent.Call && routed.intent != Intent.SendMessage) return routed
        val spoken = (routed.slots["contact"] as? String)?.trim().orEmpty()
        if (spoken.isEmpty()) return routed
        val contacts = ContactsRepository(this)
        if (!contacts.hasPermission()) return routed
        val match = contacts.findContact(spoken) ?: return routed
        // Always stash the resolved name when findContact succeeds, even
        // when it happens to equal the spoken value. The confirm-gate
        // check below uses `resolvedContact != null` as the
        // "this dispatch can actually run" signal — without unconditional
        // population, a spoken-equals-resolved pair would skip the
        // confirm prompt incorrectly.
        return routed.copy(slots = routed.slots + ("resolvedContact" to match.name))
    }

    private suspend fun resolveIntent(
        classified: IntentResult?,
        watchHint: Intent?,
        query: String,
    ): IntentResult {
        if (watchHint != null) {
            // The watch routes anything containing "remind(er)" to REMINDER_QUERY,
            // so a Reminder hint can actually be a list ("is there a reminder at
            // 2pm") or reschedule ("push my reminder to 6") query. Refine the hint
            // WITHIN the reminder family — it can't escape to Call/Sms/Media/etc,
            // so the hint's guard holds. Two refiners, in order:
            //   1. A deterministic prefix hint (e.g. interrogative + reminder →
            //      ListReminders). The embedding can't separate "is there a
            //      reminder at X" from "remind me at X" because the time dominates
            //      the cosine, so the opening words are the reliable signal.
            //   2. Otherwise a confident classifier pick (catches Reschedule).
            val prefixHint = if (watchHint == Intent.Reminder) PrefixHints.hintFor(query) else null
            val refined = when {
                prefixHint != null && prefixHint in REMINDER_FAMILY -> prefixHint
                watchHint == Intent.Reminder &&
                    classified != null &&
                    classified.intent in REMINDER_FAMILY &&
                    classified.confidence >= NluSettings.ROUTE_THRESHOLD -> classified.intent
                else -> watchHint
            }
            if (refined != watchHint) {
                Log.d(TAG, "watch hinted $watchHint; refined to $refined " +
                    "(prefix=$prefixHint classifier=${classified?.intent}@${classified?.confidence})")
            }
            val slots = slotExtractors.extract(refined, query)
            return classified?.copy(intent = refined, slots = slots)
                ?: IntentResult(
                    intent = refined,
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
        //
        // The Phase A1 "Sms → SendMessage" targeted override was
        // removed in A2 — Intent.Sms no longer has seed/bank examples
        // (everything was relabeled to SendMessage), so the classifier
        // can't return it and the override would be unreachable.
        val runnerUp = classified.alternates.firstOrNull()?.score ?: 0f
        val below = classified.confidence < NluSettings.ROUTE_THRESHOLD
        val ambiguous = !below && (classified.confidence - runnerUp) < NluSettings.ROUTE_MARGIN
        if (below || ambiguous) {
            val why = if (below) "below-threshold" else "ambiguous"
            val hint = PrefixHints.hintFor(query)
            if (hint != null) {
                val refined = PrefixHints.refineWorldTime(query, PrefixHints.refineAlarmTimer(query, hint))
                Log.d(TAG, "$why (conf=${classified.confidence} runnerUp=$runnerUp) → prefix hint $refined wins")
                val slots = slotExtractors.extract(refined, query)
                return classified.copy(intent = refined, slots = slots)
            }
            Log.d(TAG, "$why (conf=${classified.confidence} runnerUp=$runnerUp) and no prefix hint → Unknown")
            return classified.copy(intent = Intent.Unknown, slots = emptyMap())
        }
        // Confident classifier pick — trusted directly, EXCEPT for the
        // deterministic SetAlarm↔SetTimer correction. The embedding
        // confidently confuses the pair (and Whisper drops "timer"→"time"),
        // so a relative duration vs. a clock time overrides the pick. No-op
        // for every other intent. See PrefixHints.refineAlarmTimer.
        val finalIntent = PrefixHints.refineWorldTime(query, PrefixHints.refineAlarmTimer(query, classified.intent))
        if (finalIntent != classified.intent) {
            Log.d(TAG, "intent refine: ${classified.intent} → $finalIntent for \"$query\"")
        }
        val slots = slotExtractors.extract(finalIntent, query)
        return classified.copy(intent = finalIntent, slots = slots)
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

        /** Intents the watch's "remind(er)" keyword routing lumps into
         *  REMINDER_QUERY. A Reminder watch-hint may be refined into any of
         *  these by a confident classifier, but no further. */
        private val REMINDER_FAMILY = setOf(
            Intent.Reminder, Intent.ListReminders, Intent.Reschedule,
        )
    }
}
