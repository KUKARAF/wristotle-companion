package com.lazydevs.wristotle.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeZoneResolverTest {

    // --- cityMap (derived from the JVM tz table) --------------------

    @Test fun `single-word city resolves`() {
        assertEquals("Asia/Tokyo", TimeZoneResolver.resolve("tokyo")?.id)
    }

    @Test fun `multi-word city with underscore leaf resolves`() {
        assertEquals("America/New_York", TimeZoneResolver.resolve("new york")?.id)
    }

    @Test fun `case and whitespace are normalised`() {
        assertEquals("Europe/London", TimeZoneResolver.resolve("  LONDON ")?.id)
    }

    // --- ALIASES (countries, abbreviations) -------------------------

    @Test fun `country name resolves via alias`() {
        assertEquals("Asia/Tokyo", TimeZoneResolver.resolve("japan")?.id)
    }

    @Test fun `abbreviation resolves via alias`() {
        assertEquals("America/New_York", TimeZoneResolver.resolve("nyc")?.id)
        assertEquals("America/Los_Angeles", TimeZoneResolver.resolve("la")?.id)
    }

    @Test fun `uk alias beats any literal zone`() {
        assertEquals("Europe/London", TimeZoneResolver.resolve("uk")?.id)
    }

    // --- Unresolvable ------------------------------------------------

    @Test fun `gibberish resolves to null`() {
        assertNull(TimeZoneResolver.resolve("nowhereville"))
    }

    @Test fun `empty input resolves to null`() {
        assertNull(TimeZoneResolver.resolve("   "))
    }
}
