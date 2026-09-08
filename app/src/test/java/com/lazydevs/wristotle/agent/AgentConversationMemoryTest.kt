// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.agent

import com.lazydevs.wristotle.speech.nlu.agent.AgentConversationMemory
import com.lazydevs.wristotle.speech.nlu.agent.AgentReset
import com.lazydevs.wristotle.speech.nlu.agent.LlmMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the Ask Agent conversation memory + reset-keyword parsing
 *  (issue #25). Memory lives in commonMain; exercised here on the JVM. */
class AgentConversationMemoryTest {

    private var maxTurns = 5
    private var idleMs = 300_000L
    private var clock = 1_000L

    private fun mem() = AgentConversationMemory(
        maxTurns = { maxTurns },
        idleTimeoutMs = { idleMs },
        now = { clock },
    )

    @Test fun `records and returns turns oldest-first as user then assistant`() = runBlocking {
        val m = mem()
        m.recordTurn("weather in Rabat?", "Sunny, 24C.")
        m.recordTurn("and tomorrow?", "Cloudy, 21C.")
        val h = m.historyForNextTurn()
        assertEquals(4, h.size)
        assertEquals(LlmMessage.User("weather in Rabat?"), h[0])
        assertEquals("Sunny, 24C.", (h[1] as LlmMessage.Assistant).text)
        assertEquals(LlmMessage.User("and tomorrow?"), h[2])
    }

    @Test fun `history is bounded to maxTurns`() = runBlocking {
        maxTurns = 3
        val m = mem()
        repeat(6) { m.recordTurn("q$it", "a$it") }
        val h = m.historyForNextTurn()
        assertEquals(6, h.size) // 3 turns * 2 messages
        assertEquals(LlmMessage.User("q3"), h[0]) // oldest kept is turn 3
    }

    @Test fun `maxTurns zero disables memory`() = runBlocking {
        maxTurns = 0
        val m = mem()
        m.recordTurn("q", "a")
        assertTrue(m.historyForNextTurn().isEmpty())
        assertFalse(m.isActive())
    }

    @Test fun `clear empties the history`() = runBlocking {
        val m = mem()
        m.recordTurn("q", "a")
        assertTrue(m.isActive())
        m.clear()
        assertFalse(m.isActive())
        assertTrue(m.historyForNextTurn().isEmpty())
    }

    @Test fun `idle timeout drops stale context`() = runBlocking {
        idleMs = 60_000L
        val m = mem()
        clock = 1_000L
        m.recordTurn("q", "a")
        clock = 1_000L + 60_001L // just past the window
        assertTrue(m.historyForNextTurn().isEmpty())
        assertFalse(m.isActive())
    }

    @Test fun `within idle window context survives`() = runBlocking {
        idleMs = 60_000L
        val m = mem()
        clock = 1_000L
        m.recordTurn("q", "a")
        clock = 1_000L + 30_000L
        assertEquals(2, m.historyForNextTurn().size)
    }

    @Test fun `blank answers are not stored`() = runBlocking {
        val m = mem()
        m.recordTurn("q", "   ")
        assertFalse(m.isActive())
    }

    // ── AgentReset ─────────────────────────────────────────────────────

    @Test fun `reset keyword alone clears with empty remainder`() {
        val r = AgentReset.detect("new", AgentReset.DEFAULTS)
        assertEquals("", r?.remainder)
    }

    @Test fun `reset keyword with remainder returns the rest`() {
        val r = AgentReset.detect("new what's the weather", AgentReset.DEFAULTS)
        assertEquals("what's the weather", r?.remainder)
    }

    @Test fun `reset keyword with comma separator`() {
        val r = AgentReset.detect("reset, tell me a joke", AgentReset.DEFAULTS)
        assertEquals("tell me a joke", r?.remainder)
    }

    @Test fun `word merely starting with a keyword does not match`() {
        assertNull(AgentReset.detect("newspaper headlines", AgentReset.DEFAULTS))
    }

    @Test fun `non-keyword query does not match`() {
        assertNull(AgentReset.detect("what time is it", AgentReset.DEFAULTS))
    }

    @Test fun `custom keyword matches case-insensitively`() {
        val r = AgentReset.detect("Clear", listOf("clear"))
        assertEquals("", r?.remainder)
    }
}
