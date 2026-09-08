// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

/**
 * In-memory, bounded rolling history of Ask Agent turns so follow-ups
 * ("what's the weather in Rabat?" → "and tomorrow?") carry context. Each
 * turn is the user's transcript + the assistant's final answer; tool-call
 * intermediates are intentionally dropped — they'd bloat the context and
 * cost tokens/latency on every turn, which matters on a watch.
 *
 * NOT persisted: the history lives only for the companion process. That
 * gives "reset on app quit" for free (a killed companion starts fresh) and
 * keeps a conversation off disk. The other two resets are explicit:
 * [clear] (spoken keyword) and the idle timeout applied on read/record.
 *
 * App-singleton scope (held by WristotleApplication), NOT service-scoped —
 * the PebbleListenerService is recreated on every watch-app open, which
 * would otherwise wipe context mid-conversation.
 *
 * Bounds ([maxTurns]) and [idleTimeoutMs] are read live via lambdas so a
 * Settings change takes effect on the next turn without reconstruction.
 */
class AgentConversationMemory(
    private val maxTurns: () -> Int,
    private val idleTimeoutMs: () -> Long,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()
    private val turns = ArrayDeque<Turn>()
    private var lastActivityMs = 0L

    private data class Turn(val user: LlmMessage.User, val assistant: LlmMessage.Assistant)

    /**
     * History to prepend before the current user turn, as a flat
     * [System-free] list of alternating User/Assistant messages (oldest
     * first). Applies the idle timeout first, so a stale conversation is
     * dropped before it can leak into a fresh question.
     */
    suspend fun historyForNextTurn(): List<LlmMessage> = mutex.withLock {
        expireIfIdleLocked()
        turns.flatMap { listOf(it.user, it.assistant) }
    }

    /** Record a completed turn (user transcript + assistant final answer) and
     *  trim to the newest [maxTurns]. Blank answers aren't stored. */
    suspend fun recordTurn(userQuery: String, assistantText: String) = mutex.withLock {
        if (assistantText.isBlank()) return@withLock
        turns.addLast(Turn(LlmMessage.User(userQuery), LlmMessage.Assistant(text = assistantText)))
        val cap = maxTurns().coerceAtLeast(0)
        while (turns.size > cap) turns.removeFirst()
        lastActivityMs = now()
    }

    /** Explicit reset — the spoken reset keyword, or any caller that wants a
     *  clean slate. */
    suspend fun clear() = mutex.withLock {
        turns.clear()
        lastActivityMs = 0L
    }

    /** True when there's live context that would be sent on the next turn
     *  (after applying the idle timeout). Drives the watch "context on"
     *  indicator. */
    suspend fun isActive(): Boolean = mutex.withLock {
        expireIfIdleLocked()
        turns.isNotEmpty()
    }

    private fun expireIfIdleLocked() {
        val timeout = idleTimeoutMs()
        if (timeout > 0 && lastActivityMs != 0L && now() - lastActivityMs > timeout) {
            turns.clear()
            lastActivityMs = 0L
        }
    }
}

/**
 * Detects a spoken reset keyword ("new" / "forget" / "reset", or the
 * user's own list) at the START of a transcript. Matching is
 * word-boundary anchored so "newspaper" doesn't trip "new", and any
 * trailing separator is consumed so the remainder is a clean query.
 */
object AgentReset {
    /** [remainder] is what's left after stripping the keyword — empty when the
     *  keyword was the whole utterance (a bare "new" → clear + acknowledge). */
    data class Match(val remainder: String)

    fun detect(query: String, keywords: List<String>): Match? {
        val q = query.trim()
        if (q.isEmpty()) return null
        for (raw in keywords) {
            val kw = raw.trim()
            if (kw.isEmpty()) continue
            val re = Regex("(?i)^" + Regex.escape(kw) + "\\b[\\s,.:;!?-]*")
            val m = re.find(q) ?: continue
            return Match(remainder = q.substring(m.value.length).trim())
        }
        return null
    }

    /** Default reset words, mirrored in AskAgentSettings' default. */
    val DEFAULTS = listOf("new", "forget", "reset")
}
