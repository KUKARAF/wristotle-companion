// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.handlers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimeZoneResolverTest {

    // --- cityMap (derived from kotlinx-datetime's available zones) --

    @Test fun singleWordCityResolves() {
        assertEquals("Asia/Tokyo", TimeZoneResolver.resolve("tokyo")?.id)
    }

    @Test fun multiWordCityWithUnderscoreLeafResolves() {
        assertEquals("America/New_York", TimeZoneResolver.resolve("new york")?.id)
    }

    @Test fun caseAndWhitespaceAreNormalised() {
        assertEquals("Europe/London", TimeZoneResolver.resolve("  LONDON ")?.id)
    }

    // --- ALIASES (countries, abbreviations) -------------------------

    @Test fun countryNameResolvesViaAlias() {
        assertEquals("Asia/Tokyo", TimeZoneResolver.resolve("japan")?.id)
    }

    @Test fun abbreviationResolvesViaAlias() {
        assertEquals("America/New_York", TimeZoneResolver.resolve("nyc")?.id)
        assertEquals("America/Los_Angeles", TimeZoneResolver.resolve("la")?.id)
    }

    @Test fun ukAliasBeatsAnyLiteralZone() {
        assertEquals("Europe/London", TimeZoneResolver.resolve("uk")?.id)
    }

    // --- Unresolvable ------------------------------------------------

    @Test fun gibberishResolvesToNull() {
        assertNull(TimeZoneResolver.resolve("nowhereville"))
    }

    @Test fun emptyInputResolvesToNull() {
        assertNull(TimeZoneResolver.resolve("   "))
    }
}
