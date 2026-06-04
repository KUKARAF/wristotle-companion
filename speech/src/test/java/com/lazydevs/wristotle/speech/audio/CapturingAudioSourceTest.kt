package com.lazydevs.wristotle.speech.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Behaviour tests for [CapturingAudioSource] — the wrapper that lets
 * conversation-audio capture happen above the Recognizer layer.
 */
class CapturingAudioSourceTest {

    @Test
    fun `captures flat PCM and fires sink exactly once on normal completion`() = runBlocking {
        var firedCount = 0
        var captured: ShortArray? = null
        val inner = fakeSource(listOf(shortArrayOf(1, 2, 3), shortArrayOf(4, 5)))

        CapturingAudioSource(inner) { samples ->
            firedCount++
            captured = samples
        }.samples().toList()

        assertEquals(1, firedCount)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5), captured)
    }

    @Test
    fun `passes chunks through unchanged to downstream collector`() = runBlocking {
        val inner = fakeSource(listOf(shortArrayOf(1, 2), shortArrayOf(3, 4, 5)))

        val downstream = CapturingAudioSource(inner) { }.samples().toList()

        assertEquals(2, downstream.size)
        assertArrayEquals(shortArrayOf(1, 2), downstream[0])
        assertArrayEquals(shortArrayOf(3, 4, 5), downstream[1])
    }

    @Test
    fun `does not fire sink when upstream throws mid-stream`() = runBlocking {
        var firedCount = 0
        val inner = object : AudioSource {
            override val sampleRate = 16_000
            override val channelCount = 1
            override fun samples(): Flow<ShortArray> = flow {
                emit(shortArrayOf(1, 2))
                error("simulated source failure")
            }
            override fun stop() = Unit
        }

        try {
            CapturingAudioSource(inner) { firedCount++ }.samples().toList()
            fail("expected the exception to propagate")
        } catch (_: IllegalStateException) {
            // expected
        }
        assertEquals(0, firedCount)
    }

    @Test
    fun `does not fire sink on empty stream`() = runBlocking {
        var firedCount = 0
        var captured: ShortArray? = null
        val inner = fakeSource(emptyList())

        CapturingAudioSource(inner) { samples ->
            firedCount++
            captured = samples
        }.samples().toList()

        assertEquals(0, firedCount)
        assertNull(captured)
    }

    @Test
    fun `sink failures do not break the recognition path`() = runBlocking {
        val inner = fakeSource(listOf(shortArrayOf(1, 2)))

        // The sink throws — but the collect call below must still
        // complete normally (no exception leaking from the wrapper).
        val downstream = CapturingAudioSource(inner) {
            error("misbehaving sink")
        }.samples().toList()

        assertTrue(downstream.isNotEmpty())
    }

    @Test
    fun `sampleRate and channelCount delegate to the inner source`() {
        val inner = object : AudioSource {
            override val sampleRate = 48_000
            override val channelCount = 2
            override fun samples(): Flow<ShortArray> = flow {}
            override fun stop() = Unit
        }
        val wrapped = CapturingAudioSource(inner) { }
        assertEquals(48_000, wrapped.sampleRate)
        assertEquals(2, wrapped.channelCount)
    }

    private fun fakeSource(chunks: List<ShortArray>) = object : AudioSource {
        override val sampleRate = 16_000
        override val channelCount = 1
        override fun samples(): Flow<ShortArray> = flow { for (c in chunks) emit(c) }
        override fun stop() = Unit
    }
}
