// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.notes

import com.lazydevs.wristotle.speech.nlu.settings.AppendAudioMode
import com.lazydevs.wristotle.speech.nlu.settings.NoteSettings
import com.lazydevs.wristotle.speech.nlu.settings.NoteSettingsView
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoteRepositoryAppendTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FakeSettings(
        keep: Int = NoteSettings.UNLIMITED,
        mode: AppendAudioMode = AppendAudioMode.MERGE,
    ) : NoteSettingsView {
        override val keepLast: StateFlow<Int> = MutableStateFlow(keep)
        private val _mode = MutableStateFlow(mode)
        override val appendAudioMode: StateFlow<AppendAudioMode> = _mode
        fun set(m: AppendAudioMode) { _mode.value = m }
    }

    private lateinit var dao: FakeNoteDao
    private lateinit var audioDir: File
    private lateinit var audioStore: NotesAudioStore

    @Before fun setUp() {
        dao = FakeNoteDao()
        audioDir = tmp.newFolder("notes-audio")
        audioStore = NotesAudioStore(audioDir)
    }

    /** Write a minimal valid 16-bit mono 16 kHz WAV with [samples] samples. */
    private fun makeWav(name: String, samples: ShortArray): File {
        val f = tmp.newFile(name)
        val dataSize = samples.size * 2
        val total = WAV_HEADER_SIZE - 8 + dataSize
        val bytes = ByteBuffer.allocate(WAV_HEADER_SIZE + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray(Charsets.US_ASCII))
        bytes.putInt(total)
        bytes.put("WAVE".toByteArray(Charsets.US_ASCII))
        bytes.put("fmt ".toByteArray(Charsets.US_ASCII))
        bytes.putInt(16); bytes.putShort(1); bytes.putShort(1)
        bytes.putInt(16_000); bytes.putInt(32_000); bytes.putShort(2); bytes.putShort(16)
        bytes.put("data".toByteArray(Charsets.US_ASCII)); bytes.putInt(dataSize)
        for (s in samples) bytes.putShort(s)
        f.writeBytes(bytes.array())
        return f
    }

    @Test fun `append returns null when there is no previous note`() = runBlocking {
        val repo = NoteRepository(dao, audioStore, FakeSettings())
        val result = repo.append(body = "first append", atEpochMs = 100L)
        assertNull("nothing to append to → null", result)
        assertEquals(0, dao.count())
    }

    @Test fun `append updates body and bumps timestamp`() = runBlocking {
        val repo = NoteRepository(dao, audioStore, FakeSettings())
        repo.insert(body = "Hello", source = "watch", createdAtEpochMs = 100L)
        val updated = repo.append(body = "world", atEpochMs = 200L)
        assertNotNull(updated)
        assertEquals("Hello\nworld", updated!!.body)
        assertEquals(200L, updated.createdAtEpochMs)
    }

    @Test fun `MERGE concatenates onto prior wav and keeps path stable`() = runBlocking {
        val settings = FakeSettings(mode = AppendAudioMode.MERGE)
        val repo = NoteRepository(dao, audioStore, settings)
        val id = repo.insert(body = "first", source = "watch", createdAtEpochMs = 100L)

        // Seed prior audio (simulate attachAudio).
        val originalSrc = makeWav("orig.wav", shortArrayOf(1, 2, 3, 4))
        repo.attachAudio(id, originalSrc)
        val priorPath = dao.findById(id)!!.audioFilePath
        assertNotNull(priorPath)

        // Append with a new wav.
        val src = makeWav("append.wav", shortArrayOf(5, 6, 7))
        val updated = repo.append(body = "more", atEpochMs = 200L, conversationAudioFile = src)
        assertNotNull(updated)
        // Single path, unchanged.
        assertEquals(priorPath, updated!!.audioFilePath)
        // The single file now contains both clips back to back.
        val paths = NoteAudioPaths.parse(updated.audioFilePath)
        assertEquals(1, paths.size)
        val file = File(paths.first())
        // 4 + 3 samples × 2 bytes = 14 PCM bytes after the 44-byte header.
        assertEquals((WAV_HEADER_SIZE + 14).toLong(), file.length())
    }

    @Test fun `MERGE without prior audio copies as a new file`() = runBlocking {
        val settings = FakeSettings(mode = AppendAudioMode.MERGE)
        val repo = NoteRepository(dao, audioStore, settings)
        repo.insert(body = "first", source = "watch", createdAtEpochMs = 100L)

        val src = makeWav("append.wav", shortArrayOf(1, 2, 3))
        val updated = repo.append(body = "more", atEpochMs = 200L, conversationAudioFile = src)
        assertNotNull(updated)
        val paths = NoteAudioPaths.parse(updated!!.audioFilePath)
        assertEquals(1, paths.size)
        assertTrue("new file under notes-audio dir", File(paths.first()).parentFile?.absolutePath == audioDir.absolutePath)
    }

    @Test fun `SEPARATE with prior audio appends new path to the list`() = runBlocking {
        val settings = FakeSettings(mode = AppendAudioMode.SEPARATE)
        val repo = NoteRepository(dao, audioStore, settings)
        val id = repo.insert(body = "first", source = "watch", createdAtEpochMs = 100L)
        repo.attachAudio(id, makeWav("orig.wav", shortArrayOf(1, 2)))
        val priorPath = dao.findById(id)!!.audioFilePath!!

        val src = makeWav("append.wav", shortArrayOf(3, 4, 5))
        val updated = repo.append(body = "more", atEpochMs = 200L, conversationAudioFile = src)
        assertNotNull(updated)
        val paths = NoteAudioPaths.parse(updated!!.audioFilePath)
        assertEquals("now two clips on the note", 2, paths.size)
        assertEquals("first clip is the original", priorPath, paths[0])
    }

    @Test fun `SEPARATE without prior audio creates a single new entry`() = runBlocking {
        val settings = FakeSettings(mode = AppendAudioMode.SEPARATE)
        val repo = NoteRepository(dao, audioStore, settings)
        repo.insert(body = "first", source = "watch", createdAtEpochMs = 100L)

        val src = makeWav("append.wav", shortArrayOf(1, 2, 3))
        val updated = repo.append(body = "more", atEpochMs = 200L, conversationAudioFile = src)
        val paths = NoteAudioPaths.parse(updated!!.audioFilePath)
        assertEquals(1, paths.size)
    }

    @Test fun `delete removes every audio file on the note`() = runBlocking {
        val settings = FakeSettings(mode = AppendAudioMode.SEPARATE)
        val repo = NoteRepository(dao, audioStore, settings)
        val id = repo.insert(body = "first", source = "watch", createdAtEpochMs = 100L)
        repo.attachAudio(id, makeWav("orig.wav", shortArrayOf(1, 2)))
        repo.append(body = "more", atEpochMs = 200L, conversationAudioFile = makeWav("a.wav", shortArrayOf(3, 4)))

        val paths = NoteAudioPaths.parse(dao.findById(id)!!.audioFilePath)
        assertEquals(2, paths.size)
        paths.forEach { assertTrue("file present before delete", File(it).exists()) }

        repo.delete(id)
        assertNull(dao.findById(id))
        paths.forEach { assertFalse("file gone after delete", File(it).exists()) }
    }

    @Test fun `prune respects keep-last cap and deletes audio for evicted notes`() = runBlocking {
        val settings = FakeSettings(keep = 2, mode = AppendAudioMode.MERGE)
        val repo = NoteRepository(dao, audioStore, settings)
        val a = repo.insert(body = "a", source = "watch", createdAtEpochMs = 100L)
        repo.attachAudio(a, makeWav("a.wav", shortArrayOf(1, 2)))
        val aPath = dao.findById(a)!!.audioFilePath!!
        repo.insert(body = "b", source = "watch", createdAtEpochMs = 200L)
        // Inserting a third note triggers prune (oldest, `a`, is evicted).
        repo.insert(body = "c", source = "watch", createdAtEpochMs = 300L)

        assertEquals(2, dao.count())
        assertNull("evicted note row gone", dao.findById(a))
        assertFalse("evicted note's audio file deleted", File(aPath).exists())
    }
}