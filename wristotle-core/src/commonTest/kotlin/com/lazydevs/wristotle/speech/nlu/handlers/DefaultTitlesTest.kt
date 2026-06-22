// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.handlers

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Single source of truth for app-generated titles — both the handler and the
 * confirm-before-send preview call these, so a drift would desync two surfaces.
 */
class DefaultTitlesTest {

    @Test fun reminderTitle() {
        assertEquals("Buy milk", DefaultTitles.composeReminderTitle("Buy milk"))
        assertEquals(DefaultTitles.REMINDER, DefaultTitles.composeReminderTitle(null))
        assertEquals(DefaultTitles.REMINDER, DefaultTitles.composeReminderTitle("   "))
    }

    @Test fun eventBothSpoken() {
        assertEquals("Lunch with Sam", DefaultTitles.composeEventTitle("Lunch", "Sam"))
    }

    @Test fun eventExplicitAlreadyNamesAttendeeIsNotDoubleAppended() {
        // Whisper sometimes captures the with-clause inside the title — don't
        // append " with Sam" again.
        assertEquals("Lunch with Sam", DefaultTitles.composeEventTitle("Lunch with Sam", "Sam"))
        assertEquals("Lunch with SAM", DefaultTitles.composeEventTitle("Lunch with SAM", "sam")) // case-insensitive
    }

    @Test fun eventOnlyExplicit() {
        assertEquals("Standup", DefaultTitles.composeEventTitle("Standup", null))
        assertEquals("Standup", DefaultTitles.composeEventTitle("Standup", "  "))
    }

    @Test fun eventOnlyAttendeeGetsNoPrefix() {
        assertEquals("Meeting with Sam", DefaultTitles.composeEventTitle(null, "Sam"))
        assertEquals("Meeting with Sam", DefaultTitles.composeEventTitle("  ", "Sam"))
    }

    @Test fun eventNeitherFallsBackToPrefixedPlaceholder() {
        assertEquals(DefaultTitles.MEETING, DefaultTitles.composeEventTitle(null, null))
        assertEquals(DefaultTitles.MEETING, DefaultTitles.composeEventTitle("", ""))
    }
}
