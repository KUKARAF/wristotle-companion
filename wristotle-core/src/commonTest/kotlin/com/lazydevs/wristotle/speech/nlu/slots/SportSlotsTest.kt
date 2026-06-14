// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs
package com.lazydevs.wristotle.speech.nlu.slots

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SportSlotsTest {
    private val slots = SportSlots()

    private suspend fun kind(q: String): SportKind = slots.extract(q).sportKind()
    private suspend fun subject(q: String): String? = slots.extract(q).sportSubject()

    @Test fun kindStandings() = runTest {
        assertEquals(SportKind.STANDINGS, kind("premier league table"))
        assertEquals(SportKind.STANDINGS, kind("nba standings"))
        assertEquals(SportKind.STANDINGS, kind("where are arsenal in the table"))
    }

    @Test fun kindLiveBeatsLastForScore() = runTest {
        // "live score" must read as LIVE, not LAST's "score".
        assertEquals(SportKind.LIVE, kind("what's the live score"))
        assertEquals(SportKind.LIVE, kind("how are the warriors doing right now"))
    }

    @Test fun kindLast() = runTest {
        assertEquals(SportKind.LAST, kind("did arsenal win"))
        assertEquals(SportKind.LAST, kind("what was the score of the lakers game"))
        assertEquals(SportKind.LAST, kind("how did manchester united do"))
    }

    @Test fun kindNextAndDefault() = runTest {
        assertEquals(SportKind.NEXT, kind("when do the warriors play next"))
        assertEquals(SportKind.NEXT, kind("next game for arsenal"))
        assertEquals(SportKind.NEXT, kind("warriors")) // bare team → NEXT default
    }

    @Test fun subjectExtraction() = runTest {
        assertEquals("warriors", subject("when do the warriors play next"))
        assertEquals("arsenal", subject("did arsenal win"))
        assertEquals("lakers", subject("what's the live score for the lakers"))
        assertEquals("premier league", subject("premier league table"))
        assertEquals("nba", subject("nba standings"))
        assertEquals("warriors", subject("warriors"))
    }

    @Test fun pronounMeansFavorite() = runTest {
        // "did we win" → no subject (handler uses the saved favorite) + LAST.
        assertNull(subject("did we win"))
        assertEquals(SportKind.LAST, kind("did we win"))
    }
}
