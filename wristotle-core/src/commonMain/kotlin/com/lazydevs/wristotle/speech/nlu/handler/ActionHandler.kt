// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.handler

import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult

/**
 * A single capability the companion can perform in response to a watch query.
 *
 * Each handler declares exactly one [Intent] it services. [HandlerRegistry]
 * keeps a 1:1 intent→handler map; the NLU classifier picks the intent, the
 * slot extractor populates structured parameters, and the handler executes.
 *
 * To add a new feature:
 *  1. Add an entry to [Intent].
 *  2. Implement [ActionHandler] for it.
 *  3. Register it in the listener service's handler list.
 *  4. Add a SlotExtractor for the intent (if it needs slots).
 *  5. Seed a few example phrasings in SeedExamples.kt.
 *
 * R4 batch 1 — lifted from :app. Handlers themselves can stay :app-side
 * (Android types) and just implement this interface; future iOS handlers
 * implement the same interface against AVFoundation / Contacts.framework
 * etc.
 */
interface ActionHandler {
    /** Short tag identifying this handler in conversation history ("call", "sms", etc.). */
    val tag: String

    /** The intent this handler services. Used by [HandlerRegistry] for routing. */
    val intent: Intent

    /**
     * Executes the action against the classified intent + extracted slots.
     * Return a short result string for the watch chat display.
     */
    suspend fun handle(result: IntentResult): String

    /**
     * Optional card kind for a handler whose plain-text response should also
     * surface as a full-screen watch card (the CRUD reminder/task/note cards).
     * When non-null, the default [handleRich] attaches it with no [RichResult.cardData],
     * so the watch renders a text card under the kind's header (band color + icon
     * derived from the kind string). Handlers needing a *structured* card (e.g.
     * the sports scoreboard) override [handleRich] directly and ignore this.
     */
    val cardKind: String? get() = null

    /**
     * Richer variant carrying optional structured [RichResult.cardData] the
     * watch can render as a visual card (e.g. a sports scoreboard). The default
     * wraps [handle] and attaches [cardKind] (null for most handlers → a plain
     * chat bubble); only handlers producing structured card DATA override this.
     */
    suspend fun handleRich(result: IntentResult): RichResult =
        RichResult(handle(result), cardKind = cardKind)
}

/**
 * A handler response plus optional inline-widget descriptor for the watch.
 *
 * [cardKind] is the widget TYPE the watch renders ("sport_score",
 * "sport_standings", "sport_fixture", "sport_text"); [cardData] is its compact,
 * watch-parsed payload (US-0x1F fields, RS-0x1E rows). Both null = a plain chat
 * bubble. Sent together in one AppMessage frame with the response.
 */
data class RichResult(
    val response: String,
    val cardKind: String? = null,
    val cardData: String? = null,
)
