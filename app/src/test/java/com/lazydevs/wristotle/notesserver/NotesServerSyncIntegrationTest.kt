// SPDX-License-Identifier: AGPL-3.0-only

package com.lazydevs.wristotle.notesserver

import com.lazydevs.wristotle.notes.FakeNoteDao
import com.lazydevs.wristotle.notes.NoteRepository
import com.lazydevs.wristotle.notes.NotesAudioStore
import com.lazydevs.wristotle.speech.nlu.settings.AppendAudioMode
import com.lazydevs.wristotle.speech.nlu.settings.NoteSettings
import com.lazydevs.wristotle.speech.nlu.settings.NoteSettingsView
import com.lazydevs.wristotle.tasks.TaskDao
import com.lazydevs.wristotle.tasks.TaskEntity
import com.lazydevs.wristotle.tasks.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

/**
 * End-to-end run of [NotesServerSync] against a real rust_note server with
 * in-memory DAOs. Skipped unless `NOTES_SERVER_IT_URL` points at a
 * dev-mode server with an EMPTY notes repo, e.g.:
 *
 *   RUSTNOTE_ENV=dev RUSTNOTE_BIND_ADDR=127.0.0.1:18080 cargo run -p server
 *   NOTES_SERVER_IT_URL=http://127.0.0.1:18080 ./gradlew :app:testDebugUnitTest \
 *       --tests '*NotesServerSyncIntegrationTest*'
 */
class NotesServerSyncIntegrationTest {

    @get:Rule val tmp = TemporaryFolder()

    private val baseUrl: String? = System.getenv("NOTES_SERVER_IT_URL")
    private lateinit var scope: CoroutineScope
    private lateinit var api: NotesServerApi
    private lateinit var noteDao: FakeNoteDao
    private lateinit var taskDao: FakeTaskDao
    private lateinit var notes: NoteRepository
    private lateinit var tasks: TaskRepository
    private lateinit var sync: NotesServerSync
    private val session = FakeSession()
    private val daily = NoteMarkdown.dailyNoteId(LocalDate.now())

    @Before fun setUp() {
        assumeTrue("NOTES_SERVER_IT_URL not set", baseUrl != null)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        api = NotesServerApi(tokenProvider = { "dev" }, baseUrl = baseUrl!!)
        noteDao = FakeNoteDao()
        taskDao = FakeTaskDao()
        notes = NoteRepository(noteDao, NotesAudioStore(tmp.newFolder("audio")), UnlimitedSettings)
        tasks = TaskRepository(taskDao)
        sync = NotesServerSync(
            auth = session,
            api = NotesServerApi(tokenProvider = { session.token }, baseUrl = baseUrl),
            syncDao = FakeSyncDao(),
            noteDao = noteDao,
            taskDao = taskDao,
            deleteLocalNote = notes::deleteLocalOnly,
            scope = scope,
        )
        notes.syncHooks = sync
        tasks.syncHooks = sync
    }

    @After fun tearDown() {
        if (::scope.isInitialized) scope.cancel()
    }

    private suspend fun syncNow() {
        sync.syncIfStale(maxAgeMs = 0, timeoutMs = 60_000)
        assertNull("sync error", sync.status.value.lastError)
    }

    private suspend fun serverContent(id: String): String? = api.getNote(id)?.content

    @Test fun fullRoundTrip() = runBlocking {
        // Pre-existing server data + pre-existing local (offline) data.
        api.createNote("projects/ideas", "# Ideas\nbuild a boat\n")
        api.createNote(daily, "---\nplan: true\n---\n- [ ] server task #fb\n")
        val localNoteId = notes.insert("Pick up the parcel tomorrow", "watch", System.currentTimeMillis() - 60_000)
        tasks.add("buy bread", "watch")
        val plants = tasks.add("water plants", "watch")
        tasks.markCompleted(plants)

        // 1. First sign-in: uploads local data, mirrors server data down.
        session.signIn()
        syncNow()
        val remoteNotes = api.listNotes().map { it.id }
        val wristotleNote = remoteNotes.single { it.startsWith("wristotle/") }
        assertTrue(wristotleNote, wristotleNote.endsWith("pick-up-the-parcel-tomorrow"))
        assertEquals("Pick up the parcel tomorrow\n", serverContent(wristotleNote))
        val diary = serverContent(daily)!!
        assertTrue(diary, diary.contains("- [ ] server task #fb\n- [ ] buy bread\n- [x] water plants\n"))
        assertEquals(listOf("build a boat".let { "# Ideas\n$it" }), noteDao.allForBackup().filter { it.id != localNoteId }.map { it.body })
        assertEquals(setOf("server task", "buy bread", "water plants"), taskDao.rows.map { it.text }.toSet())
        assertEquals(3, taskDao.rows.size)

        // 2. Idempotent: a second sync changes nothing.
        syncNow()
        assertEquals(2, noteDao.allForBackup().size)
        assertEquals(3, taskDao.rows.size)
        assertEquals(3, api.listNotes().size) // ideas, wristotle note, daily note

        // 3. Append goes to the Wristotle note even though "ideas" is newer.
        val appended = notes.append("and the letters", System.currentTimeMillis())
        assertEquals(localNoteId, appended!!.id)
        syncNow()
        assertEquals("Pick up the parcel tomorrow\nand the letters\n", serverContent(wristotleNote))

        // 4. Complete / reopen / delete edit the daily note in place.
        val bread = taskDao.rows.single { it.text == "buy bread" }
        val server = taskDao.rows.single { it.text == "server task" }
        tasks.markCompleted(bread.id)
        tasks.markPending(plants)
        tasks.delete(server.id)
        syncNow()
        val diary2 = serverContent(daily)!!
        assertTrue(diary2, diary2.contains("- [x] buy bread\n- [ ] water plants\n"))
        assertFalse(diary2, diary2.contains("server task"))
        assertEquals(2, taskDao.rows.size)
        assertTrue(taskDao.rows.single { it.text == "buy bread" }.completed)

        // 5. Changes made on the server show up locally.
        val ideas = api.getNote("projects/ideas")!!
        api.updateNote("projects/ideas", "# Ideas\nbuild a bigger boat\n", ideas.meta.version)
        val d = api.getNote(daily)!!
        api.updateNote(daily, d.content + "- [ ] added on web\n", d.meta.version)
        syncNow()
        assertTrue(noteDao.allForBackup().any { it.body == "# Ideas\nbuild a bigger boat" })
        assertNotNull(taskDao.rows.singleOrNull { it.text == "added on web" && !it.completed })

        // 6. Deleting locally deletes on the server; deleting on the server deletes locally.
        notes.delete(localNoteId)
        api.deleteNote("projects/ideas")
        syncNow()
        assertTrue(api.listNotes().none { it.id == wristotleNote })
        assertEquals(0, noteDao.allForBackup().size)

        // 7. New note while connected lands in wristotle/.
        notes.insert("Remember the milk", "watch", System.currentTimeMillis())
        syncNow()
        val created = api.listNotes().single { it.id.startsWith("wristotle/") }
        assertEquals("Remember the milk\n", serverContent(created.id))
    }

    // ------------------------------------------------------------------ fakes

    private object UnlimitedSettings : NoteSettingsView {
        override val keepLast: StateFlow<Int> = MutableStateFlow(NoteSettings.UNLIMITED)
        override val appendAudioMode: StateFlow<AppendAudioMode> = MutableStateFlow(AppendAudioMode.MERGE)
    }

    private class FakeSession : NotesServerSession {
        private val state = MutableStateFlow<NotesServerAccount?>(null)
        override val account: StateFlow<NotesServerAccount?> = state
        override val token: String? get() = state.value?.token
        override fun clear() { state.value = null }
        fun signIn() { state.value = NotesServerAccount("dev", "admin") }
    }

    class FakeTaskDao : TaskDao {
        val rows = mutableListOf<TaskEntity>()
        private var nextId = 1L
        private val flow = MutableStateFlow<List<TaskEntity>>(emptyList())
        @Synchronized private fun emit() { flow.value = rows.toList() }
        private fun pending() = rows.filter { !it.completed }.sortedByDescending { it.createdAtEpochMs }
        private fun done() = rows.filter { it.completed }.sortedByDescending { it.completedAtEpochMs }
        override suspend fun insert(task: TaskEntity): Long =
            task.copy(id = nextId++).also { rows += it; emit() }.id
        override suspend fun update(task: TaskEntity) {
            val i = rows.indexOfFirst { it.id == task.id }
            if (i >= 0) rows[i] = task
            emit()
        }
        override fun observePending(): Flow<List<TaskEntity>> = flow.map { pending() }
        override fun observeCompleted(): Flow<List<TaskEntity>> = flow.map { done() }
        override suspend fun listPending() = pending()
        override suspend fun listCompleted() = done()
        override suspend fun listAll() = pending() + done()
        override fun observeAll(): Flow<List<TaskEntity>> = flow.map { pending() + done() }
        override suspend fun findMostRecentPending() = pending().firstOrNull()
        override suspend fun findById(id: Long) = rows.firstOrNull { it.id == id }
        override suspend fun searchPending(needle: String) = pending().filter { it.text.lowercase().contains(needle) }
        override suspend fun markCompleted(id: Long, timestamp: Long) {
            findById(id)?.let { update(it.copy(completed = true, completedAtEpochMs = timestamp)) }
        }
        override suspend fun markPending(id: Long) {
            findById(id)?.let { update(it.copy(completed = false, completedAtEpochMs = null)) }
        }
        override suspend fun deleteById(id: Long) { rows.removeAll { it.id == id }; emit() }
        override suspend fun deleteAll() { rows.clear(); emit() }
        override suspend fun countPending() = pending().size
        override suspend fun count() = rows.size
        override suspend fun allForBackup() = rows.sortedBy { it.id }
    }

    private class FakeSyncDao : SyncDao {
        private val notes = LinkedHashMap<Long, NoteLink>()
        private val tasks = LinkedHashMap<Long, TaskLink>()
        private val ops = mutableListOf<PendingOp>()
        private var nextOp = 1L
        override suspend fun noteLinks() = notes.values.toList()
        override suspend fun noteLink(localId: Long) = notes[localId]
        override suspend fun putNoteLink(link: NoteLink) { notes[link.localId] = link }
        override suspend fun deleteNoteLink(localId: Long) { notes.remove(localId) }
        override suspend fun taskLinks() = tasks.values.toList()
        override suspend fun taskLink(localId: Long) = tasks[localId]
        override suspend fun putTaskLink(link: TaskLink) { tasks[link.localId] = link }
        override suspend fun deleteTaskLink(localId: Long) { tasks.remove(localId) }
        override suspend fun enqueue(op: PendingOp): Long = nextOp++.also { ops += op.copy(id = it) }
        override suspend fun pendingOps() = ops.toList()
        override suspend fun pendingOpCount() = ops.size
        override suspend fun deleteOps(ids: List<Long>) { ops.removeAll { it.id in ids } }
        override suspend fun clearNoteLinks() = notes.clear()
        override suspend fun clearTaskLinks() = tasks.clear()
        override suspend fun clearOps() = ops.clear()
    }
}
