package com.lazydevs.wristotle.service

import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.logging.WristotleLog as Log
import com.lazydevs.wristotle.notes.Note
import com.lazydevs.wristotle.notes.NotesResponseFormatter
import com.lazydevs.wristotle.tasks.TasksWireFrame
import com.lazydevs.wristotle.transport.MessageKeys
import com.lazydevs.wristotle.transport.PebbleTransport
import com.lazydevs.wristotle.transport.int32
import io.rebble.pebblekit2.common.model.PebbleDictionary
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "WatchRequestRouter"

/**
 * Handles the four watch-initiated request/response pairs that aren't
 * voice queries:
 *  - NOTES_REQUEST → NOTES_RESPONSE (snapshot of the user's notes)
 *  - NOTE_DETAIL_REQUEST(index) → NOTE_DETAIL_RESPONSE (one note's body)
 *  - TASKS_REQUEST(filter) → TASKS_RESPONSE (framed task list)
 *  - TASK_COMPLETE_REQUEST(id) → TASK_COMPLETE_RESPONSE (status string)
 *
 * Lifted out of [PebbleListenerService.onMessageReceived] so the
 * dispatch hot path stays focused on voice-query routing. Also lets
 * the note-detail truncation + task-filter mapping land in unit tests
 * without standing the service up.
 */
class WatchRequestRouter(
    private val app: WristotleApplication,
    private val transport: PebbleTransport,
) {

    /** Last batch of notes shipped via NOTES_RESPONSE — the watch refers
     *  back by zero-based index when it wants a single note's full body
     *  via NOTE_DETAIL_REQUEST. Replaced on every NOTES_REQUEST. */
    private val lastNotesSnapshot = AtomicReference<List<Note>>(emptyList())

    /**
     * If [data] matches one of the watch-request keys, handle it and
     * return true. The caller should `return ReceiveResult.Ack` when this
     * returns true. Returns false to let the caller fall through to the
     * voice-query path.
     */
    suspend fun tryHandle(data: PebbleDictionary): Boolean = when {
        data[MessageKeys.NOTES_REQUEST] != null -> handleNotesRequest().let { true }
        data[MessageKeys.TASKS_REQUEST] != null -> handleTasksRequest(data).let { true }
        data.int32(MessageKeys.TASK_COMPLETE_REQUEST) != null ->
            handleTaskCompleteRequest(data.int32(MessageKeys.TASK_COMPLETE_REQUEST)!!).let { true }
        data.int32(MessageKeys.NOTE_DETAIL_REQUEST) != null ->
            handleNoteDetailRequest(data.int32(MessageKeys.NOTE_DETAIL_REQUEST)!!).let { true }
        else -> false
    }

    private suspend fun handleNotesRequest() {
        val notes = app.noteRepository.mostRecent(NotesResponseFormatter.MAX_NOTES)
        lastNotesSnapshot.set(notes)
        val payload = NotesResponseFormatter.format(notes)
        Log.d(TAG, "NOTES_REQUEST → sending ${notes.size} notes (${payload.length} chars)")
        transport.sendNotesResponse(payload)
    }

    private suspend fun handleTasksRequest(data: PebbleDictionary) {
        val filter = data.int32(MessageKeys.TASKS_REQUEST) ?: MessageKeys.TASKS_FILTER_PENDING
        val list = when (filter) {
            MessageKeys.TASKS_FILTER_COMPLETED -> app.taskRepository.listCompleted()
            MessageKeys.TASKS_FILTER_ALL       -> app.taskRepository.listAll()
            else                               -> app.taskRepository.listPending()
        }
        val payload = TasksWireFrame.encode(list)
        Log.d(TAG, "TASKS_REQUEST filter=$filter → sending ${list.size} tasks (${payload.length} chars)")
        transport.sendTasksResponse(payload)
    }

    private suspend fun handleTaskCompleteRequest(taskId: Int) {
        val task = app.taskRepository.findById(taskId.toLong())
        val reply = when {
            task == null -> "Task no longer exists"
            task.completed -> "Task already completed"
            else -> {
                app.taskRepository.markCompleted(task.id)
                "Completed: ${task.text}"
            }
        }
        Log.d(TAG, "TASK_COMPLETE_REQUEST id=$taskId → $reply")
        transport.sendTaskCompleteResponse(reply)
    }

    private suspend fun handleNoteDetailRequest(detailIndex: Int) {
        val snapshot = lastNotesSnapshot.get()
        val note = snapshot.getOrNull(detailIndex)
        val payload = if (note == null) "" else formatDetail(note)
        Log.d(TAG, "NOTE_DETAIL_REQUEST idx=$detailIndex → ${payload.length} chars")
        transport.sendNoteDetailResponse(payload)
    }

    private fun formatDetail(note: Note): String {
        val header = android.text.format.DateFormat
            .format("MMM d, h:mm a", note.createdAtEpochMs).toString()
        // Reserve header + 2 newlines + ellipsis from the budget so the
        // truncation happens on the body, not on the timestamp.
        val budgetForBody = MessageKeys.NOTE_DETAIL_MAX_CHARS - header.length - 2
        val body = if (note.body.length > budgetForBody) {
            note.body.substring(0, budgetForBody - 1) + "…"
        } else note.body
        return "$header\n\n$body"
    }
}
