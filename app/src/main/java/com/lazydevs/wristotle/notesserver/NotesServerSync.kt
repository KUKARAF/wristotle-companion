// SPDX-License-Identifier: AGPL-3.0-only

package com.lazydevs.wristotle.notesserver

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.lazydevs.wristotle.notes.Note
import com.lazydevs.wristotle.notes.NoteDao
import com.lazydevs.wristotle.tasks.TaskDao
import com.lazydevs.wristotle.tasks.TaskEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "NotesServerSync"

/**
 * Hooks the note/task repositories call so local mutations reach the
 * notes server. All no-ops while signed out — the app then behaves
 * exactly like upstream Wristotle (local Room only).
 */
interface RemoteSyncHooks {
    val isActive: Boolean
    /** Local note ids that "append to my last note" must skip (server notes
     *  outside the Wristotle folder — appending to those would be surprising). */
    suspend fun notAppendableNoteIds(): Set<Long>
    fun onLocalCreate()
    /** Bounded pull before answering a read (watch list, voice "list tasks"). */
    suspend fun refreshIfStale(timeoutMs: Long)
    suspend fun onNoteAppended(localId: Long, text: String)
    suspend fun onNoteDeleting(localId: Long)
    suspend fun onTaskDoneChanged(localId: Long, done: Boolean)
    suspend fun onTaskDeleting(localId: Long)
}

data class NotesServerStatus(
    val syncing: Boolean = false,
    val lastSuccessMs: Long = 0,
    val lastError: String? = null,
)

/**
 * Two-way sync between the local Room tables (which stay the source the
 * UI, watch and voice handlers read — so everything keeps working
 * offline) and rust_note:
 *
 *  - Notes ↔ markdown notes. New local notes are created under
 *    `wristotle/`; every other server note (except daily notes, drawings
 *    and `_settings/`) is mirrored down so it shows on the phone + watch.
 *  - Tasks ↔ checkbox lines in daily notes (`diary/YYYY-MM-DD`), the same
 *    set rust_note's /todo board shows. New tasks go into today's note.
 *
 * Push runs first (creates = unlinked local rows, plus queued deltas);
 * pull only runs once the outbox drained, so it never clobbers
 * unsent local changes.
 */
class NotesServerSync(
    private val context: Context,
    private val auth: NotesServerAuth,
    private val api: NotesServerApi,
    private val syncDao: SyncDao,
    private val noteDao: NoteDao,
    private val taskDao: TaskDao,
    private val deleteLocalNote: suspend (Long) -> Unit,
    private val scope: CoroutineScope,
) : RemoteSyncHooks {

    private val mutex = Mutex()
    private val _status = MutableStateFlow(NotesServerStatus())
    val status: StateFlow<NotesServerStatus> = _status.asStateFlow()

    @Volatile private var syncRequested = false
    @Volatile private var workerRunning = false

    override val isActive: Boolean get() = auth.token != null

    fun start() {
        // Sign-in / sign-out edge: sync immediately on sign-in (this is also
        // what uploads pre-existing local notes + tasks the first time).
        scope.launch {
            auth.account.distinctUntilChangedBy { it?.token }.collect { account ->
                if (account != null) requestSync() else _status.value = NotesServerStatus()
            }
        }
        scope.launch {
            while (true) {
                delay(PERIODIC_MS)
                if (isActive) requestSync()
            }
        }
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (isActive) requestSync()
                }
            })
        }.onFailure { Log.w(TAG, "network callback unavailable", it) }
    }

    /** Fire-and-forget, debounced + coalesced. */
    fun requestSync() {
        if (!isActive) return
        syncRequested = true
        if (workerRunning) return
        workerRunning = true
        scope.launch {
            try {
                while (syncRequested) {
                    delay(DEBOUNCE_MS)
                    syncRequested = false
                    mutex.withLock { runSync() }
                }
            } finally {
                workerRunning = false
            }
            if (syncRequested) requestSync()
        }
    }

    /** Sync now and wait (bounded) — used right before answering the watch
     *  or when a screen opens, so what's shown is fresh. */
    suspend fun syncIfStale(maxAgeMs: Long = STALE_MS, timeoutMs: Long = 5_000) {
        if (!isActive) return
        if (System.currentTimeMillis() - _status.value.lastSuccessMs < maxAgeMs) return
        withTimeoutOrNull(timeoutMs) { mutex.withLock { runSync() } }
    }

    suspend fun logout() {
        api.logout()
        auth.clear()
    }

    // ---------------------------------------------------------------- hooks

    override suspend fun notAppendableNoteIds(): Set<Long> =
        syncDao.noteLinks()
            .filterNot { it.remoteId.startsWith(NotesServerConfig.WRISTOTLE_FOLDER + "/") }
            .mapTo(HashSet()) { it.localId }

    override fun onLocalCreate() = requestSync()

    override suspend fun refreshIfStale(timeoutMs: Long) = syncIfStale(timeoutMs = timeoutMs)

    override suspend fun onNoteAppended(localId: Long, text: String) {
        if (!isActive) return
        // Unlinked: the pending create will upload the full (appended) body.
        syncDao.noteLink(localId)?.let {
            syncDao.enqueue(PendingOp(kind = PendingOp.NOTE_APPEND, noteId = it.remoteId, text = text))
        }
        requestSync()
    }

    override suspend fun onNoteDeleting(localId: Long) {
        if (!isActive) return
        syncDao.noteLink(localId)?.let {
            syncDao.enqueue(PendingOp(kind = PendingOp.NOTE_DELETE, noteId = it.remoteId))
            syncDao.deleteNoteLink(localId)
        }
        requestSync()
    }

    override suspend fun onTaskDoneChanged(localId: Long, done: Boolean) {
        if (!isActive) return
        syncDao.taskLink(localId)?.let {
            syncDao.enqueue(
                PendingOp(
                    kind = if (done) PendingOp.TASK_DONE else PendingOp.TASK_REOPEN,
                    noteId = it.noteId, line = it.line, rawText = it.rawText,
                )
            )
        }
        requestSync()
    }

    override suspend fun onTaskDeleting(localId: Long) {
        if (!isActive) return
        syncDao.taskLink(localId)?.let {
            syncDao.enqueue(PendingOp(kind = PendingOp.TASK_DELETE, noteId = it.noteId, line = it.line, rawText = it.rawText))
            syncDao.deleteTaskLink(localId)
        }
        requestSync()
    }

    // ----------------------------------------------------------------- sync

    private suspend fun runSync() {
        if (!isActive) return
        _status.update { it.copy(syncing = true) }
        try {
            pushNoteCreates()
            pushOps()
            pushTaskCreates()
            pullNotes()
            pullTasks()
            _status.value = NotesServerStatus(lastSuccessMs = System.currentTimeMillis())
        } catch (e: CancellationException) {
            _status.update { it.copy(syncing = false) }
            throw e
        } catch (e: NotesServerHttpException) {
            Log.w(TAG, "sync failed", e)
            if (e.status == 401) {
                auth.clear()
                _status.value = NotesServerStatus(lastError = "Session expired — please log in again")
            } else {
                _status.update { it.copy(syncing = false, lastError = e.message) }
            }
        } catch (e: IOException) {
            Log.w(TAG, "sync failed (network)", e)
            _status.update { it.copy(syncing = false, lastError = "Offline or server unreachable (${e.javaClass.simpleName})") }
        } catch (e: Exception) {
            Log.e(TAG, "sync failed (unexpected)", e)
            _status.update { it.copy(syncing = false, lastError = e.message ?: e.javaClass.simpleName) }
        }
    }

    private suspend fun pushNoteCreates() {
        val linked = syncDao.noteLinks().mapTo(HashSet()) { it.localId }
        for (note in noteDao.allForBackup().filter { it.id !in linked }) {
            val title = NoteMarkdown.newNoteTitle(note.body, note.createdAtEpochMs)
            val meta = createWithUniqueTitle(title, note.body.trimEnd() + "\n")
            val current = noteDao.findById(note.id)
            if (current == null) {
                // Deleted locally while the POST was in flight.
                api.deleteNote(meta.id)
                continue
            }
            var latest = meta
            if (current.body != note.body) {
                latest = api.updateNote(meta.id, current.body.trimEnd() + "\n", meta.version)
            }
            syncDao.putNoteLink(NoteLink(note.id, latest.id, parseInstant(latest.updatedAt)))
        }
    }

    private suspend fun createWithUniqueTitle(title: String, content: String): RemoteNoteMeta {
        var attempt = 1
        while (true) {
            val candidate = if (attempt == 1) title else "$title $attempt"
            try {
                return api.createNote(candidate, content)
            } catch (e: NotesServerHttpException) {
                if (e.status != 409 || attempt >= 5) throw e
                attempt++
            }
        }
    }

    private suspend fun pushOps() {
        for (op in syncDao.pendingOps()) {
            try {
                applyOp(op)
            } catch (e: NotesServerHttpException) {
                // Auth / transient server trouble: keep the op, abort this sync.
                if (e.status == 401 || e.status == 408 || e.status == 429 || e.status >= 500) throw e
                Log.w(TAG, "dropping op ${op.kind} on ${op.noteId}: ${e.message}")
            }
            syncDao.deleteOps(listOf(op.id))
        }
    }

    private suspend fun applyOp(op: PendingOp) {
        when (op.kind) {
            PendingOp.NOTE_DELETE -> api.deleteNote(op.noteId)
            PendingOp.NOTE_APPEND -> editNote(op.noteId) { NoteMarkdown.appendText(it, op.text) }
            PendingOp.TASK_DONE -> editNote(op.noteId) { NoteMarkdown.setDone(it, op.line, op.rawText, done = true) }
            PendingOp.TASK_REOPEN -> editNote(op.noteId) { NoteMarkdown.setDone(it, op.line, op.rawText, done = false) }
            PendingOp.TASK_DELETE -> editNote(op.noteId) { NoteMarkdown.removeTask(it, op.line, op.rawText) }
            else -> Log.w(TAG, "unknown op kind ${op.kind}")
        }
    }

    /** GET → transform → PUT with optimistic concurrency; retries on 409.
     *  A null transform result (target line vanished) or a missing note is a no-op. */
    private suspend fun editNote(noteId: String, transform: (String) -> String?): RemoteNoteMeta? {
        repeat(MAX_CONFLICT_RETRIES) {
            val note = api.getNote(noteId) ?: return null
            val updated = transform(note.content) ?: return null
            if (updated == note.content) return note.meta
            try {
                return api.updateNote(noteId, updated, note.meta.version)
            } catch (e: NotesServerHttpException) {
                if (e.status != 409) throw e
            }
        }
        throw NotesServerHttpException(409, "note $noteId kept changing; will retry")
    }

    private suspend fun pushTaskCreates() {
        val linked = syncDao.taskLinks().mapTo(HashSet()) { it.localId }
        val unlinked = taskDao.allForBackup().filter { it.id !in linked }
        if (unlinked.isEmpty()) return
        val noteId = NoteMarkdown.dailyNoteId(LocalDate.now())
        if (api.getNote(noteId) == null) {
            try {
                api.createNote(noteId, NoteMarkdown.DAILY_TEMPLATE)
            } catch (e: NotesServerHttpException) {
                if (e.status != 409) throw e
            }
        }
        var lineNumbers: List<Int> = emptyList()
        editNote(noteId) { content ->
            val (updated, lines) = NoteMarkdown.appendTasks(content, unlinked.map { it.text to it.completed })
            lineNumbers = lines
            updated
        } ?: throw NotesServerHttpException(404, "daily note $noteId disappeared")
        unlinked.forEachIndexed { i, task ->
            val link = TaskLink(task.id, noteId, lineNumbers[i], NoteMarkdown.taskLineText(task.text))
            syncDao.putTaskLink(link)
            // Changed locally while we were uploading (no op was queued
            // because the row wasn't linked yet): reconcile now.
            val current = taskDao.findById(task.id)
            when {
                current == null -> onTaskDeleting(task.id)
                current.completed != task.completed -> onTaskDoneChanged(task.id, current.completed)
            }
        }
    }

    private suspend fun pullNotes() {
        val metas = api.listNotes().filter { NoteMarkdown.isSyncableNote(it.id) }
        val remoteIds = metas.mapTo(HashSet()) { it.id }
        val links = syncDao.noteLinks()
        for (link in links) {
            if (link.remoteId !in remoteIds && !hasPendingOps(link.remoteId)) {
                deleteLocalNote(link.localId)
                syncDao.deleteNoteLink(link.localId)
            }
        }
        val linkByRemote = links.associateBy { it.remoteId }
        val window = metas.sortedByDescending { parseInstant(it.updatedAt) }.take(MAX_PULLED_NOTES)
        for (meta in window) {
            val updatedAt = parseInstant(meta.updatedAt)
            val link = linkByRemote[meta.id]
            if (link != null && link.remoteUpdatedAtMs == updatedAt) continue
            val remote = api.getNote(meta.id) ?: continue
            if (hasPendingOps(meta.id)) continue
            val body = NoteMarkdown.displayBody(remote.content)
            val local = link?.let { noteDao.findById(it.localId) }
            if (local != null) {
                if (local.body != body || local.createdAtEpochMs != updatedAt) {
                    noteDao.updateBodyAndTimestamp(local.id, body, updatedAt)
                }
                syncDao.putNoteLink(link.copy(remoteUpdatedAtMs = updatedAt))
            } else {
                if (link != null) syncDao.deleteNoteLink(link.localId)
                val id = noteDao.insert(Note(body = body, createdAtEpochMs = updatedAt, source = SOURCE_SERVER))
                syncDao.putNoteLink(NoteLink(id, meta.id, updatedAt))
            }
        }
    }

    private suspend fun pullTasks() {
        val today = LocalDate.now()
        val cutoff = today.minusDays(DONE_WINDOW_DAYS)
        val todos = api.todos(scope = "diary", includeDone = true).filter { todo ->
            !todo.done || (todo.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today) >= cutoff
        }
        val links = syncDao.taskLinks()
        val busy = syncDao.pendingOps().mapTo(HashSet()) { it.noteId to it.rawText.trim() }
        val byKey = links.groupBy { it.noteId to it.rawText.trim() }.mapValues { it.value.toMutableList() }
        val seen = HashSet<Long>()
        for (todo in todos) {
            val key = todo.noteId to todo.text.trim()
            val display = todo.textClean.ifBlank { todo.text }.trim()
            val order = orderKey(todo, today)
            val link = byKey[key]?.removeFirstOrNull()
            val local = link?.let { taskDao.findById(it.localId) }
            if (link != null && local != null) {
                seen += local.id
                if (key in busy) continue
                val updated = local.copy(
                    text = display,
                    completed = todo.done,
                    createdAtEpochMs = order,
                    completedAtEpochMs = if (todo.done) local.completedAtEpochMs ?: order else null,
                )
                if (updated != local) taskDao.update(updated)
                if (link.line != todo.line) syncDao.putTaskLink(link.copy(line = todo.line))
                continue
            }
            if (link != null) syncDao.deleteTaskLink(link.localId)
            val id = taskDao.insert(
                TaskEntity(
                    text = display,
                    completed = todo.done,
                    createdAtEpochMs = order,
                    completedAtEpochMs = if (todo.done) order else null,
                    source = SOURCE_SERVER,
                )
            )
            syncDao.putTaskLink(TaskLink(id, todo.noteId, todo.line, todo.text))
            seen += id
        }
        for (link in links) {
            if (link.localId in seen || (link.noteId to link.rawText.trim()) in busy) continue
            taskDao.deleteById(link.localId)
            syncDao.deleteTaskLink(link.localId)
        }
    }

    private suspend fun hasPendingOps(noteId: String): Boolean =
        syncDao.pendingOps().any { it.noteId == noteId }

    /** Newest day first, later lines of a day first — matches "newest first" lists. */
    private fun orderKey(todo: RemoteTodo, today: LocalDate): Long {
        val date = todo.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
        return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + todo.line * 1_000L
    }

    private fun parseInstant(value: String): Long =
        runCatching { Instant.parse(value).toEpochMilli() }
            .recoverCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .getOrDefault(0L)

    companion object {
        const val SOURCE_SERVER = "notes_server"
        private const val DEBOUNCE_MS = 1_500L
        private const val PERIODIC_MS = 10 * 60_000L
        private const val STALE_MS = 60_000L
        private const val MAX_CONFLICT_RETRIES = 4
        private const val MAX_PULLED_NOTES = 300
        private const val DONE_WINDOW_DAYS = 14L
    }
}
