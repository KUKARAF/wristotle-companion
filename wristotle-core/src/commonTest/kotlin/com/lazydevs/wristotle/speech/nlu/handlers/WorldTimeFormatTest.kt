// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.handlers

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * World-clock presentation. Tokyo (UTC+9, no DST) keeps the [format]
 * integration cases deterministic; the local zone is injected (UTC) so the
 * offset/weekday suffix doesn't depend on where the test runs.
 */
class WorldTimeFormatTest {

    @Test fun offsetPhrase() {
        assertEquals("same time", WorldTimeFormat.offsetPhrase(0))
        assertEquals("13h ahead", WorldTimeFormat.offsetPhrase(13 * 60))
        assertEquals("8h behind", WorldTimeFormat.offsetPhrase(-8 * 60))
        assertEquals("1h30m ahead", WorldTimeFormat.offsetPhrase(90))
        assertEquals("30m behind", WorldTimeFormat.offsetPhrase(-30))
        assertEquals("5h45m ahead", WorldTimeFormat.offsetPhrase(5 * 60 + 45)) // e.g. India / Nepal-ish
    }

    @Test fun formatTime12Hour() {
        assertEquals("12:00 AM", WorldTimeFormat.formatTime(LocalDateTime(2026, 6, 21, 0, 0)))
        assertEquals("7:05 AM", WorldTimeFormat.formatTime(LocalDateTime(2026, 6, 21, 7, 5)))
        assertEquals("12:00 PM", WorldTimeFormat.formatTime(LocalDateTime(2026, 6, 21, 12, 0)))
        assertEquals("1:30 PM", WorldTimeFormat.formatTime(LocalDateTime(2026, 6, 21, 13, 30)))
        assertEquals("11:09 PM", WorldTimeFormat.formatTime(LocalDateTime(2026, 6, 21, 23, 9)))
    }

    @Test fun titleCase() {
        assertEquals("Tokyo", WorldTimeFormat.titleCase("tokyo"))
        assertEquals("New York", WorldTimeFormat.titleCase("new york"))
        assertEquals("Los Angeles", WorldTimeFormat.titleCase("los angeles"))
        assertEquals("NYC", WorldTimeFormat.titleCase("nyc"))
        assertEquals("UK", WorldTimeFormat.titleCase("uk"))
    }

    @Test fun formatAssemblesFullStringSameDay() {
        val s = WorldTimeFormat.format(
            targetZone = TimeZone.of("Asia/Tokyo"),
            localZone = TimeZone.UTC,
            spoken = "tokyo",
            now = Instant.parse("2026-06-21T00:00:00Z"),
        )
        assertEquals("It's 9:00 AM in Tokyo\n(9h ahead).", s)
    }

    @Test fun formatPrependsWeekdayOnDifferentDay() {
        // At 20:00 UTC it's already the next calendar day in Tokyo (05:00).
        val s = WorldTimeFormat.format(
            targetZone = TimeZone.of("Asia/Tokyo"),
            localZone = TimeZone.UTC,
            spoken = "tokyo",
            now = Instant.parse("2026-06-21T20:00:00Z"),
        )
        // Weekday is present (3-letter, comma) ahead of the offset.
        assertTrue(s.startsWith("It's 5:00 AM in Tokyo\n("), "got: $s")
        assertTrue(Regex("""\([A-Z][a-z]{2}, 9h ahead\)\.$""").containsMatchIn(s), "got: $s")
    }
}
