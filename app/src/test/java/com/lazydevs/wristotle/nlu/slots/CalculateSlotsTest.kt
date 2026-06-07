// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.nlu.slots

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalculateSlotsTest {

    private fun expr(query: String): String? =
        runBlocking { CalculateSlots().extract(query)["expression"] as? String }

    // --- Operator-word normalisation --------------------------------

    @Test fun `plus`() { assertEquals("25 + 17", expr("what's 25 plus 17")) }
    @Test fun `minus`() { assertEquals("100 - 30", expr("what is 100 minus 30")) }
    @Test fun `times`() { assertEquals("12 * 8", expr("12 times 8")) }
    @Test fun `divided by`() { assertEquals("96 / 4", expr("what's 96 divided by 4")) }
    @Test fun `x as multiply`() { assertEquals("6 * 7", expr("6 x 7")) }

    // --- Lead-in stripping ------------------------------------------

    @Test fun `calculate lead-in is stripped`() {
        assertEquals("45 + 55", expr("calculate 45 plus 55"))
    }

    @Test fun `how much is lead-in is stripped`() {
        assertEquals("9 * 9", expr("how much is 9 times 9"))
    }

    // --- Percent forms ----------------------------------------------

    @Test fun `percent of with symbol`() {
        assertEquals("( 15 / 100 * 80 )", expr("what's 15% of 80"))
    }

    @Test fun `percent of with word`() {
        assertEquals("( 30 / 100 * 200 )", expr("how much is 30 percent of 200"))
    }

    @Test fun `percent off is a discount`() {
        assertEquals("( 50 - 20 / 100 * 50 )", expr("20% off 50"))
    }

    // --- No math present --------------------------------------------

    @Test fun `non-math query yields empty`() {
        assertNull(expr("what's the weather like"))
    }
}