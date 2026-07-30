// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package lazydevs.wristotle.speech.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slots.queryHasExplicitMeridiem
import com.lazydevs.wristotle.speech.nlu.slots.queryNamesExplicitDate
import com.lazydevs.wristotle.speech.nlu.slots.rolledToNextFutureOccurrence

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure tests for the past-time rollover shared by [ReminderSlots] (issue #13)
 * and [CreateEventSlots]. Runs on the Android host and the iOS Sim. June 12 is
 * far from any DST transition, so day/12-h steps are exact in UTC.
 */
class TimeRolloverTest {

    private val utc = TimeZone.UTC

    // --- explicit meridiem → whole-day steps --------------------------------

    @Test fun `explicit am time rolls to the next day, not the other meridiem`() {
        // "1 a.m." dictated at 11 a.m. — the user named a.m., so it must become
        // tomorrow 1 a.m., NOT today's 1 p.m.
        val now = Instant.parse("2026-06-12T11:00:00Z")
        val oneAmToday = Instant.parse("2026-06-12T01:00:00Z")
        assertEquals(
            Instant.parse("2026-06-13T01:00:00Z"),
            oneAmToday.rolledToNextFutureOccurrence(now, utc, ambiguousMeridiem = false),
        )
    }

    @Test fun `explicit time still ahead today is untouched`() {
        val now = Instant.parse("2026-06-12T11:00:00Z")
        val ninePmToday = Instant.parse("2026-06-12T21:00:00Z")
        assertEquals(
            ninePmToday,
            ninePmToday.rolledToNextFutureOccurrence(now, utc, ambiguousMeridiem = false),
        )
    }

    // --- bare hour → 12-hour steps (next time the dial shows it) -------------

    @Test fun `bare hour past this morning rolls to this evening`() {
        // "at 8" dictated at 9 a.m. — next 8 o'clock is 8 p.m. today.
        val now = Instant.parse("2026-06-12T09:00:00Z")
        val eightAmToday = Instant.parse("2026-06-12T08:00:00Z")
        assertEquals(
            Instant.parse("2026-06-12T20:00:00Z"),
            eightAmToday.rolledToNextFutureOccurrence(now, utc, ambiguousMeridiem = true),
        )
    }

    @Test fun `bare hour past both halves today rolls to tomorrow morning`() {
        // "at 8" dictated at 9 p.m. — both 8 a.m. and 8 p.m. are gone, so the
        // next 8 o'clock is 8 a.m. tomorrow.
        val now = Instant.parse("2026-06-12T21:00:00Z")
        val eightAmToday = Instant.parse("2026-06-12T08:00:00Z")
        assertEquals(
            Instant.parse("2026-06-13T08:00:00Z"),
            eightAmToday.rolledToNextFutureOccurrence(now, utc, ambiguousMeridiem = true),
        )
    }

    @Test fun `bare hour still ahead today is untouched`() {
        val now = Instant.parse("2026-06-12T09:00:00Z")
        val tenAmToday = Instant.parse("2026-06-12T10:00:00Z")
        assertEquals(
            tenAmToday,
            tenAmToday.rolledToNextFutureOccurrence(now, utc, ambiguousMeridiem = true),
        )
    }

    // --- explicit dates are NOT day-rolled (query-aware overload) -----------

    @Test fun `a query naming an explicit date is honoured as spoken, not walked forward`() {
        // Regression: "july twenty ninth at 3 pm" parses to Jul 29; on Jul 30
        // (after 3 pm) the day-roll used to walk it 29 → 30 → 31. The query-aware
        // overload must return the parsed instant unchanged when the query names
        // a date (guard runs before any roll, so it's zone-independent here).
        val now = Instant.parse("2026-07-30T18:00:00Z")
        val jul29_3pm = Instant.parse("2026-07-29T15:00:00Z")
        assertEquals(
            jul29_3pm,
            jul29_3pm.rolledToNextFutureOccurrence(now, "schedule dentist july twenty ninth at 3 pm"),
        )
    }

    @Test fun `queryNamesExplicitDate detects dates but not bare clock times`() {
        assertTrue(queryNamesExplicitDate("july twenty ninth at 3 pm"))
        assertTrue(queryNamesExplicitDate("dentist on the 29th at 3 pm"))
        assertTrue(queryNamesExplicitDate("meeting 6/29 at noon"))
        assertTrue(queryNamesExplicitDate("event dec 3rd at 9"))
        assertFalse(queryNamesExplicitDate("remind me at 8 to take meds"))
        assertFalse(queryNamesExplicitDate("schedule a meeting at one am"))
        // "second" as a duration/word must not read as a date ordinal.
        assertFalse(queryNamesExplicitDate("remind me in 30 seconds to stir"))
    }

    // --- meridiem detection -------------------------------------------------

    @Test fun `explicit meridiem words are detected`() {
        assertTrue(queryHasExplicitMeridiem("remind me at 1 a.m. to take meds"))
        assertTrue(queryHasExplicitMeridiem("remind me at 8pm to call"))
        assertTrue(queryHasExplicitMeridiem("remind me at 8 tonight"))
        assertTrue(queryHasExplicitMeridiem("remind me at noon to eat"))
        assertTrue(queryHasExplicitMeridiem("remind me in the morning to run"))
    }

    @Test fun `bare hour has no explicit meridiem`() {
        assertFalse(queryHasExplicitMeridiem("remind me at 8 to take meds"))
        assertFalse(queryHasExplicitMeridiem("remind me at 8 o'clock to leave"))
    }

    @Test fun `meridiem detector does not fire inside ordinary words`() {
        // "am" inside "exam" / a name must not count as a meridiem.
        assertFalse(queryHasExplicitMeridiem("remind me at 5 to study for the exam"))
    }
}
