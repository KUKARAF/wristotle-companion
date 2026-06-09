// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slots.*

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetTimerSlotsTest {

    private fun extract(query: String): Map<String, Any> =
        runBlocking { SetTimerSlots().extract(query) }

    // --- Digit-form durations ---------------------------------------

    @Test fun `digit minutes`() {
        assertEquals(600, extract("set a timer for 10 minutes")["seconds"])
    }

    @Test fun `digit min shorthand`() {
        assertEquals(300, extract("timer for 5 min")["seconds"])
    }

    @Test fun `digit seconds`() {
        assertEquals(30, extract("timer for 30 seconds")["seconds"])
    }

    @Test fun `digit hours`() {
        assertEquals(3600, extract("set a timer for 1 hour")["seconds"])
    }

    @Test fun `bare digit defaults to minutes`() {
        // Unlike MediaSeek (bare → seconds): a timer of "10" means 10 min.
        assertEquals(600, extract("set a timer for 10")["seconds"])
    }

    @Test fun `compound hour and minutes are summed`() {
        assertEquals(5400, extract("set a timer for 1 hour 30 minutes")["seconds"])
    }

    @Test fun `leading-noun shape with digit`() {
        assertEquals(1200, extract("set a 20 minute timer")["seconds"])
    }

    // --- Word-form durations ----------------------------------------

    @Test fun `word number minutes`() {
        assertEquals(600, extract("timer for ten minutes")["seconds"])
    }

    @Test fun `eleven minutes resolves`() {
        // Regression — codeberg #11. WORD_NUMBERS pre-fix had a gap from
        // 11..19; "set a timer for eleven minutes" returned no duration.
        assertEquals(660, extract("set a timer for eleven minutes")["seconds"])
    }

    @Test fun `compound tens resolve in both spacings`() {
        assertEquals(21 * 60, extract("set a timer for twenty one minutes")["seconds"])
        assertEquals(45 * 60, extract("set a timer for forty-five minutes")["seconds"])
    }

    @Test fun `an hour resolves to 3600`() {
        assertEquals(3600, extract("set a timer for an hour")["seconds"])
    }

    @Test fun `article before timer does not add a phantom minute`() {
        // The bug this guards: word-form path counting "a" in "set A timer"
        // as 1 minute. "set a timer for ten minutes" must be exactly 600,
        // not 660.
        assertEquals(600, extract("set a timer for ten minutes")["seconds"])
    }

    // --- No duration → empty map (handler reports the failure) ------

    @Test fun `no duration returns empty map`() {
        assertTrue(extract("set a timer").isEmpty())
    }

    @Test fun `word-form without a unit returns empty map`() {
        // "set a timer for ten" — bare word-number, no unit. The word
        // path requires an explicit unit (to dodge the article bug), so
        // this is a miss rather than a wrong guess.
        assertTrue(extract("set a timer for ten").isEmpty())
    }
}