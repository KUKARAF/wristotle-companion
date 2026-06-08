// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.speech.nlu.handlers.Calculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorTest {

    private fun value(expr: String): Double {
        val r = Calculator.evaluate(expr)
        assertTrue("expected a value for \"$expr\" but got $r", r is Calculator.Result.Value)
        return (r as Calculator.Result.Value).number
    }

    // --- Arithmetic + precedence ------------------------------------

    @Test fun `addition`() { assertEquals(42.0, value("25 + 17"), 1e-9) }
    @Test fun `subtraction`() { assertEquals(70.0, value("100 - 30"), 1e-9) }
    @Test fun `multiplication`() { assertEquals(96.0, value("12 * 8"), 1e-9) }
    @Test fun `division`() { assertEquals(24.0, value("96 / 4"), 1e-9) }

    @Test fun `precedence multiply before add`() {
        assertEquals(14.0, value("2 + 3 * 4"), 1e-9)
    }

    @Test fun `parentheses override precedence`() {
        assertEquals(20.0, value("(2 + 3) * 4"), 1e-9)
    }

    @Test fun `percent-of expansion evaluates`() {
        // "15% of 80" is rewritten upstream to this form.
        assertEquals(12.0, value("( 15 / 100 * 80 )"), 1e-9)
    }

    @Test fun `unary minus`() {
        assertEquals(-5.0, value("-5"), 1e-9)
        assertEquals(7.0, value("10 + -3"), 1e-9)
    }

    @Test fun `decimal numbers`() {
        assertEquals(3.5, value("1.5 + 2"), 1e-9)
    }

    // --- Error results ----------------------------------------------

    @Test fun `division by zero is reported`() {
        assertEquals(Calculator.Result.DivByZero, Calculator.evaluate("5 / 0"))
    }

    @Test fun `empty is unparseable`() {
        assertEquals(Calculator.Result.Unparseable, Calculator.evaluate("   "))
    }

    @Test fun `trailing junk is unparseable`() {
        assertEquals(Calculator.Result.Unparseable, Calculator.evaluate("2 2"))
    }

    @Test fun `dangling operator is unparseable`() {
        assertEquals(Calculator.Result.Unparseable, Calculator.evaluate("2 +"))
    }

    @Test fun `unbalanced paren is unparseable`() {
        assertEquals(Calculator.Result.Unparseable, Calculator.evaluate("(2 + 3"))
    }
}