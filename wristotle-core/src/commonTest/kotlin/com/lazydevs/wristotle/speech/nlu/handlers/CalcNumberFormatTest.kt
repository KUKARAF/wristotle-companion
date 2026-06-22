// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.handlers

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards [formatCalcNumber] — the hand-rolled round-to-4dp + trailing-zero
 * trim that replaced BigDecimal for commonMain. The kind of numeric formatting
 * that breaks subtly: negatives, zero, results that round up to a whole, and
 * the whole-number fast path.
 */
class CalcNumberFormatTest {

    @Test
    fun wholeNumbersDropTheDecimal() {
        assertEquals("12", formatCalcNumber(12.0))
        assertEquals("0", formatCalcNumber(0.0))
        assertEquals("-5", formatCalcNumber(-5.0))
        assertEquals("999999999999", formatCalcNumber(999999999999.0))
    }

    @Test
    fun fractionalRoundsToFourPlaces() {
        assertEquals("0.3333", formatCalcNumber(1.0 / 3.0))   // 0.33333… → 0.3333
        assertEquals("0.6667", formatCalcNumber(2.0 / 3.0))   // 0.66666… → 0.6667 (rounds up)
    }

    @Test
    fun trailingZerosAreTrimmed() {
        assertEquals("1.5", formatCalcNumber(1.5))
        assertEquals("1.2", formatCalcNumber(6.0 / 5.0))      // 1.2000 → 1.2
        assertEquals("2.25", formatCalcNumber(2.25))
    }

    @Test
    fun negativeFractionalKeepsSign() {
        assertEquals("-1.5", formatCalcNumber(-1.5))
        assertEquals("-0.3333", formatCalcNumber(-1.0 / 3.0))
    }

    @Test
    fun fractionalThatRoundsToWholeDropsDecimals() {
        assertEquals("2", formatCalcNumber(2.00001))          // → 2.0000 → "2"
        assertEquals("1", formatCalcNumber(0.99999))          // 9999.9 → 10000 → "1"
    }
}
