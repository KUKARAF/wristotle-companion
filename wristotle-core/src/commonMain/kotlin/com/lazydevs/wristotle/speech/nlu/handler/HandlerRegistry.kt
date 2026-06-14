// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.handler

import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult

/**
 * Result of dispatching an [IntentResult] through the [HandlerRegistry].
 *
 * @property response  Text sent back to the watch chat UI.
 * @property handler   Short tag identifying which handler ran ("call", "sms", …).
 * @property success   Heuristic — false if the response is a known failure
 *                     prefix ("Contact not found", "Couldn't…", "Error:",
 *                     "Unknown command"). Drives the success/failure badge
 *                     in conversation history and gates implicit learning.
 */
data class HandlerResult(
    val response: String,
    val handler: String,
    val success: Boolean,
    /** Optional inline-widget descriptor for the watch (see [RichResult]). */
    val cardKind: String? = null,
    val cardData: String? = null,
)

/**
 * Maps each [Intent] to exactly one [ActionHandler] and dispatches the
 * classified result. Replaces the prior linear `canHandle` scan now that
 * the NLU layer makes routing deterministic from the intent itself.
 *
 * Multiple handlers claiming the same intent is a programming error —
 * Kotlin's `associateBy` will silently drop earlier ones; we throw
 * eagerly so the misconfiguration shows up at startup, not at first use.
 *
 * R4 batch 1 — lifted from :app. Pure dispatch — no platform
 * dependencies. The handlers it routes to may still be platform-specific.
 */
class HandlerRegistry(handlers: List<ActionHandler>) {

    private val byIntent: Map<Intent, ActionHandler> = run {
        val grouped = handlers.groupBy { it.intent }
        val duplicates = grouped.filter { it.value.size > 1 }
        require(duplicates.isEmpty()) {
            "Duplicate intent handlers: " + duplicates.map { (intent, list) ->
                "$intent → ${list.map { it.tag }}"
            }.joinToString("; ")
        }
        grouped.mapValues { (_, list) -> list.first() }
    }

    suspend fun dispatch(result: IntentResult): HandlerResult {
        val handler = byIntent[result.intent]
        return try {
            if (handler != null) {
                val rich = handler.handleRich(result)
                HandlerResult(
                    rich.response, handler.tag,
                    success = isSuccessResponse(rich.response),
                    cardKind = rich.cardKind, cardData = rich.cardData,
                )
            } else {
                HandlerResult(
                    response = "Unknown command: ${result.rawQuery}",
                    handler = "unknown",
                    success = false,
                )
            }
        } catch (e: Exception) {
            HandlerResult(
                response = "Error: ${e.message ?: "Action failed"}",
                handler = "error",
                success = false,
            )
        }
    }

    companion object {
        private val FAILURE_PREFIXES = listOf(
            "Contact not found",
            "Couldn't",
            "Error:",
            "Unknown command",
            "Failed",
            "No phone target",
            "Query too long",
            "Contacts permission",
            "SMS permission",
            "No contact",
            "No message",
        )

        fun isSuccessResponse(response: String): Boolean =
            FAILURE_PREFIXES.none { response.startsWith(it) }
    }
}
