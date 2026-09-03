// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package lazydevs.wristotle.speech.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slots.*

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class SlotUtilsTest {

    // --- stripTrailingEmphasis ----------------------------------------------

    @Test fun `single emphatic word is preserved - could be polite filler`() {
        // "john please" might be a polite contact suffix; one word isn't
        // enough signal to strip. The threshold is 2+ for ambiguity.
        assertEquals("john please", stripTrailingEmphasis("john please"))
    }

    @Test fun `two trailing emphatic words get stripped`() {
        assertEquals("john", stripTrailingEmphasis("john yes yes"))
    }

    @Test fun `three trailing emphatic words get stripped`() {
        assertEquals("john", stripTrailingEmphasis("john, yes, yes, yes"))
    }

    @Test fun `comma-separated emphatic run gets stripped`() {
        assertEquals("call john", stripTrailingEmphasis("call john, yes, yes, yes."))
    }

    @Test fun `mixed emphatic vocabulary gets stripped`() {
        assertEquals("text mom", stripTrailingEmphasis("text mom ok ok ok please"))
    }

    @Test fun `nothing to strip leaves text alone`() {
        assertEquals("call mom", stripTrailingEmphasis("call mom"))
    }

    @Test fun `trailing punctuation alone is trimmed`() {
        assertEquals("call john", stripTrailingEmphasis("call john."))
    }

    // --- cleanNameToken -----------------------------------------------------

    @Test fun `trailing comma gets stripped from a name token`() {
        assertEquals("john", cleanNameToken("john,"))
    }

    @Test fun `quoted name token gets cleaned`() {
        assertEquals("john", cleanNameToken("\"john\""))
    }

    @Test fun `leading and trailing whitespace gets trimmed`() {
        assertEquals("john", cleanNameToken("  john  "))
    }

    @Test fun `clean token passes through unchanged`() {
        assertEquals("John", cleanNameToken("John"))
    }

    // --- stripVerbBody ------------------------------------------------------

    private val verbs = Regex("(?i)\\b(play|resume)\\b")
    private val fillers = Regex("(?i)\\b(the|some|please|on)\\b")

    @Test fun `stripVerbBody strips a leading verb and returns the body`() {
        assertEquals("spotify", stripVerbBody("play spotify", verbs, fillers))
    }

    @Test fun `stripVerbBody strips fillers anywhere in the query`() {
        assertEquals("youtube", stripVerbBody("play the youtube please", verbs, fillers))
    }

    @Test fun `stripVerbBody lowercases the input`() {
        assertEquals("spotify", stripVerbBody("Play SPOTIFY", verbs, fillers))
    }

    @Test fun `stripVerbBody collapses runs of whitespace`() {
        assertEquals("spotify", stripVerbBody("play    spotify", verbs, fillers))
    }

    @Test fun `stripVerbBody trims trailing sentence punctuation`() {
        assertEquals("spotify", stripVerbBody("play spotify.", verbs, fillers))
        assertEquals("spotify", stripVerbBody("play spotify,", verbs, fillers))
        assertEquals("spotify", stripVerbBody("play spotify!", verbs, fillers))
        assertEquals("spotify", stripVerbBody("play spotify?", verbs, fillers))
    }

    @Test fun `stripVerbBody returns empty when only the verb is present`() {
        assertEquals("", stripVerbBody("play", verbs, fillers))
    }

    @Test fun `stripVerbBody returns empty when verb plus only fillers`() {
        assertEquals("", stripVerbBody("play the some please", verbs, fillers))
    }

    // --- trailingTimeClauseRegex --------------------------------------------

    private val reminderStrip = trailingTimeClauseRegex(setOf("at", "in", "by", "on", "next", "this"))

    @Test fun `trailing time clause is stripped`() {
        assertEquals("call mom", "call mom at 5pm".replace(reminderStrip, ""))
    }

    @Test fun `lead-in inside a word is not matched`() {
        // the "at" in "chat" must not trigger a strip (the title-truncation bug).
        assertEquals("chat with bob", "chat with bob".replace(reminderStrip, ""))
    }

    @Test fun `lead-in not in the set is left alone`() {
        // "to" isn't a reminder lead-in — "remind me to call" keeps its task.
        assertEquals("call mom to confirm", "call mom to confirm".replace(reminderStrip, ""))
    }

    @Test fun `reschedule lead-in set strips a to-clause`() {
        val strip = trailingTimeClauseRegex(setOf("to", "until", "at"))
        assertEquals("gym", "gym to 6pm".replace(strip, ""))
    }

    // --- WORD_NUMBERS -------------------------------------------------------

    @Test fun `word numbers cover counts and durations`() {
        assertEquals(3, WORD_NUMBERS["three"])
        assertEquals(10, WORD_NUMBERS["ten"])
        assertEquals(45, WORD_NUMBERS["forty five"])
        assertEquals(45, WORD_NUMBERS["forty-five"])
        assertEquals(60, WORD_NUMBERS["sixty"])
    }

    @Test fun `teens are recognised`() {
        // Regression: "set a timer for eleven minutes" failed pre-fix because
        // 11..19 weren't in the table. The other teens fail the same way.
        assertEquals(11, WORD_NUMBERS["eleven"])
        assertEquals(12, WORD_NUMBERS["twelve"])
        assertEquals(13, WORD_NUMBERS["thirteen"])
        assertEquals(14, WORD_NUMBERS["fourteen"])
        assertEquals(16, WORD_NUMBERS["sixteen"])
        assertEquals(17, WORD_NUMBERS["seventeen"])
        assertEquals(18, WORD_NUMBERS["eighteen"])
        assertEquals(19, WORD_NUMBERS["nineteen"])
    }

    @Test fun `compound tens work in both spacings`() {
        assertEquals(21, WORD_NUMBERS["twenty one"])
        assertEquals(21, WORD_NUMBERS["twenty-one"])
        assertEquals(99, WORD_NUMBERS["ninety nine"])
        assertEquals(99, WORD_NUMBERS["ninety-nine"])
        assertEquals(70, WORD_NUMBERS["seventy"])
        assertEquals(80, WORD_NUMBERS["eighty"])
    }

    @Test fun `compound ordinals resolve as one unit (spoken dates)`() {
        // Bug: "july twenty ninth" normalised to "july 20 ninth" because only
        // the bare "twenty" matched, dropping the day AND the time. Compound
        // ordinals must map as a unit.
        assertEquals(29, WORD_NUMBERS["twenty ninth"])
        assertEquals(29, WORD_NUMBERS["twenty-ninth"])
        assertEquals(21, WORD_NUMBERS["twenty first"])
        assertEquals(23, WORD_NUMBERS["twenty third"])
        assertEquals(31, WORD_NUMBERS["thirty first"])
        assertEquals(25, WORD_NUMBERS["twenty fifth"])
    }

    // ── parseDurationSeconds — "and a half" fractions (issue #22) ──────────────

    @Test fun `n and a half hours - the reported case`() {
        // "set an alarm for two and a half hours from now" used to return null
        // here and fall through to the clock parser, which read "two" as 2:00.
        assertEquals(9000, parseDurationSeconds("two and a half hours from now"))
    }

    @Test fun `an hour and a half keeps the half`() {
        // Previously dropped the half and returned 3600.
        assertEquals(5400, parseDurationSeconds("set a timer for an hour and a half"))
    }

    @Test fun `one and a half hours`() {
        assertEquals(5400, parseDurationSeconds("one and a half hours"))
    }

    @Test fun `digit and a half hours`() {
        assertEquals(9000, parseDurationSeconds("2 and a half hours"))
    }

    @Test fun `hours and a half trailing form`() {
        assertEquals(9000, parseDurationSeconds("two hours and a half"))
    }

    @Test fun `half an hour`() {
        assertEquals(1800, parseDurationSeconds("half an hour"))
    }

    @Test fun `half a minute`() {
        assertEquals(30, parseDurationSeconds("half a minute"))
    }

    @Test fun `plain compound duration still sums`() {
        assertEquals(3840, parseDurationSeconds("an hour and four minutes"))
        assertEquals(5400, parseDurationSeconds("1 hour 30 minutes"))
        assertEquals(600, parseDurationSeconds("10 minutes"))
    }

    @Test fun `bare number defaults to minutes`() {
        assertEquals(600, parseDurationSeconds("10"))
    }

    @Test fun `no duration returns null`() {
        assertNull(parseDurationSeconds("call mom"))
    }
}
