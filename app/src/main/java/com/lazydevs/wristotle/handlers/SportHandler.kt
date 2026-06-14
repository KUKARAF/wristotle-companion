// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs
package com.lazydevs.wristotle.handlers

import com.lazydevs.sportskapi.SportDataSource
import com.lazydevs.sportskapi.SportSubject
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.speech.nlu.handler.ActionHandler
import com.lazydevs.wristotle.speech.nlu.settings.SportSettings
import com.lazydevs.wristotle.speech.nlu.slots.SportKind
import com.lazydevs.wristotle.speech.nlu.slots.sportKind
import com.lazydevs.wristotle.speech.nlu.slots.sportSubject
import com.lazydevs.wristotle.speech.nlu.sport.SportFormat

/**
 * Handles [Intent.SportScore] over the generic `sportskapi` [SportDataSource]
 * (ESPN-backed today, behind the neutral API). Resolves the spoken team — or
 * falls back to the first saved favorite — then dispatches by kind and renders
 * a short watch line via [SportFormat]. Read-only; not in the confirm gate.
 */
class SportHandler(
    private val source: SportDataSource,
    private val settings: SportSettings,
) : ActionHandler {

    override val tag: String = "sport"
    override val intent: Intent = Intent.SportScore

    override suspend fun handle(result: IntentResult): String {
        val kind = result.slots.sportKind()
        val spoken = result.slots.sportSubject()
        val subject = resolve(spoken)
            ?: return if (spoken == null) SportFormat.NO_SUBJECT else SportFormat.NOT_FOUND
        return when (kind) {
            SportKind.NEXT -> SportFormat.next(source.nextEvent(subject), subject.name)
            SportKind.LAST -> SportFormat.last(source.lastEvent(subject), subject.name)
            SportKind.LIVE -> {
                // "what's the score" is ambiguous: show the live game if one is
                // in progress, else fall back to the most recent result so the
                // user isn't dead-ended with "no live game".
                val live = source.liveEvent(subject)
                if (live is com.lazydevs.sportskapi.SportResult.NotFound) {
                    SportFormat.last(source.lastEvent(subject), subject.name)
                } else {
                    SportFormat.live(live, subject.name)
                }
            }
            SportKind.STANDINGS -> SportFormat.standings(source.standings(subject))
        }
    }

    /** Named team → resolve via the library; otherwise the first saved favorite. */
    private suspend fun resolve(spoken: String?): SportSubject? {
        val pref = settings.preferredSport.value.ifBlank { null }
        return if (spoken != null) source.resolveTeam(spoken, pref)
        else settings.favorites.value.firstOrNull()
    }
}
