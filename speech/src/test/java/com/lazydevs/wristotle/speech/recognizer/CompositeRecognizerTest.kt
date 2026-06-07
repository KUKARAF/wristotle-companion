// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.recognizer

import android.speech.SpeechRecognizer
import com.lazydevs.wristotle.speech.audio.AudioSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour tests for [CompositeRecognizer]'s failover logic — verified
 * with pure-Kotlin fake [Recognizer]s so the test JVM never needs to
 * boot Android or speak HTTP.
 */
class CompositeRecognizerTest {

    @Test
    fun `primary success short-circuits — secondary is never invoked`() = runBlocking {
        val primary = FakeRecognizer.success("from primary")
        val secondary = FakeRecognizer.notInvoked()

        val events = CompositeRecognizer(primary, secondary)
            .transcribe(fakeSource(twoChunks))
            .toList()

        assertEquals(0, secondary.invocationCount)
        assertEquals(TranscriptionEvent.Final("from primary"), events.last())
    }

    @Test
    fun `primary error falls back to secondary success`() = runBlocking {
        val primary = FakeRecognizer.error(SpeechRecognizer.ERROR_NETWORK, "cloud down")
        val secondary = FakeRecognizer.success("from secondary")

        val events = CompositeRecognizer(primary, secondary)
            .transcribe(fakeSource(twoChunks))
            .toList()

        assertEquals(1, secondary.invocationCount)
        assertEquals(TranscriptionEvent.Final("from secondary"), events.last())
        // The user never sees the primary's error since the secondary recovered.
        assertTrue(events.none { it is TranscriptionEvent.Error })
    }

    @Test
    fun `both fail surfaces the primary's error message`() = runBlocking {
        val primary = FakeRecognizer.error(SpeechRecognizer.ERROR_NETWORK, "cloud 503")
        val secondary = FakeRecognizer.error(SpeechRecognizer.ERROR_CLIENT, "no model")

        val events = CompositeRecognizer(primary, secondary)
            .transcribe(fakeSource(twoChunks))
            .toList()

        val terminal = events.last() as TranscriptionEvent.Error
        assertEquals(SpeechRecognizer.ERROR_NETWORK, terminal.code)
        assertEquals("cloud 503", terminal.message)
    }

    @Test
    fun `composite emits a single SpeechStarted, suppressing inner duplicates`() = runBlocking {
        // Primary fails so secondary runs too — both fakes would each emit
        // SpeechStarted internally, but composite owns that event.
        val primary = FakeRecognizer.error(SpeechRecognizer.ERROR_AUDIO, "boom")
        val secondary = FakeRecognizer.success("ok")

        val events = CompositeRecognizer(primary, secondary)
            .transcribe(fakeSource(twoChunks))
            .toList()

        assertEquals(1, events.count { it == TranscriptionEvent.SpeechStarted })
        assertEquals(1, events.count { it == TranscriptionEvent.SpeechEnded })
    }

    @Test
    fun `empty audio surfaces SPEECH_TIMEOUT and skips both recognizers`() = runBlocking {
        val primary = FakeRecognizer.notInvoked()
        val secondary = FakeRecognizer.notInvoked()

        val events = CompositeRecognizer(primary, secondary)
            .transcribe(fakeSource(emptyList()))
            .toList()

        assertEquals(0, primary.invocationCount)
        assertEquals(0, secondary.invocationCount)
        val terminal = events.last() as TranscriptionEvent.Error
        assertEquals(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, terminal.code)
    }

    @Test
    fun `requestAbort forwards to both recognizers`() {
        val primary = FakeRecognizer.success("p")
        val secondary = FakeRecognizer.success("s")

        CompositeRecognizer(primary, secondary).requestAbort()

        assertTrue(primary.abortRequested)
        assertTrue(secondary.abortRequested)
    }

    @Test
    fun `close propagates to both recognizers`() {
        val primary = FakeRecognizer.success("p")
        val secondary = FakeRecognizer.success("s")

        CompositeRecognizer(primary, secondary).close()

        assertTrue(primary.closed)
        assertTrue(secondary.closed)
    }

    @Test
    fun `secondary sees the same buffered audio that the source delivered`() = runBlocking {
        val primary = FakeRecognizer.error(SpeechRecognizer.ERROR_NETWORK, "drop")
        val secondary = FakeRecognizer.success("ok")

        CompositeRecognizer(primary, secondary)
            .transcribe(fakeSource(twoChunks))
            .toList()

        val seen = secondary.lastReceivedSamples
        assertNotNull(seen)
        assertEquals(twoChunks.sumOf { it.size }, seen!!.size)
    }

    // ── fixtures ────────────────────────────────────────────────────────

    private val twoChunks = listOf(
        ShortArray(160) { it.toShort() },
        ShortArray(160) { (it + 1000).toShort() },
    )

    /** Cold AudioSource that emits a fixed list of chunks. */
    private fun fakeSource(chunks: List<ShortArray>) = object : AudioSource {
        override val sampleRate = 16_000
        override val channelCount = 1
        override fun samples(): Flow<ShortArray> = flow { for (c in chunks) emit(c) }
        override fun stop() = Unit
    }

    /**
     * Pure-Kotlin [Recognizer] double. Records how many times
     * [transcribe] was collected, the flattened audio it saw, and
     * whether [requestAbort] / [close] were called.
     */
    private class FakeRecognizer(
        private val emit: suspend (kotlinx.coroutines.flow.FlowCollector<TranscriptionEvent>, ShortArray) -> Unit,
    ) : Recognizer {
        var invocationCount = 0
            private set
        var lastReceivedSamples: ShortArray? = null
            private set
        var abortRequested = false
            private set
        var closed = false
            private set

        override fun transcribe(source: AudioSource): Flow<TranscriptionEvent> = flow {
            invocationCount++
            // Drain the source so we can assert on what we saw.
            val chunks = ArrayList<ShortArray>()
            source.samples().collect { chunks.add(it) }
            val flat = ShortArray(chunks.sumOf { it.size })
            var off = 0
            for (c in chunks) { c.copyInto(flat, off); off += c.size }
            lastReceivedSamples = flat
            emit(this, flat)
        }

        override fun requestAbort() { abortRequested = true }
        override fun close() { closed = true }

        companion object {
            fun success(text: String) = FakeRecognizer { sink, _ ->
                sink.emit(TranscriptionEvent.SpeechStarted)
                sink.emit(TranscriptionEvent.SpeechEnded)
                sink.emit(TranscriptionEvent.Final(text))
            }

            fun error(code: Int, message: String) = FakeRecognizer { sink, _ ->
                sink.emit(TranscriptionEvent.SpeechStarted)
                sink.emit(TranscriptionEvent.SpeechEnded)
                sink.emit(TranscriptionEvent.Error(code, message))
            }

            /** Verifies it's never called — emits nothing if it is. */
            fun notInvoked() = FakeRecognizer { _, _ -> }
        }
    }
}