// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.handlers

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalculatorTest {

    private fun value(expr: String): Double {
        val r = Calculator.evaluate(expr)
        assertTrue(r is Calculator.Result.Value, "expected a value for \"$expr\" but got $r")
        return (r as Calculator.Result.Value).number
    }

    private fun assertClose(expected: Double, actual: Double, tol: Double = 1e-9) {
        assertTrue(abs(expected - actual) < tol, "expected $expected ± $tol but got $actual")
    }

    // --- Arithmetic + precedence ------------------------------------

    @Test fun addition() { assertClose(42.0, value("25 + 17")) }
    @Test fun subtraction() { assertClose(70.0, value("100 - 30")) }
    @Test fun multiplication() { assertClose(96.0, value("12 * 8")) }
    @Test fun division() { assertClose(24.0, value("96 / 4")) }

    @Test fun precedenceMultiplyBeforeAdd() {
        assertClose(14.0, value("2 + 3 * 4"))
    }

    @Test fun parenthesesOverridePrecedence() {
        assertClose(20.0, value("(2 + 3) * 4"))
    }

    @Test fun percentOfExpansionEvaluates() {
        // "15% of 80" is rewritten upstream to this form.
        assertClose(12.0, value("( 15 / 100 * 80 )"))
    }

    @Test fun unaryMinus() {
        assertClose(-5.0, value("-5"))
        assertClose(7.0, value("10 + -3"))
    }

    @Test fun decimalNumbers() {
        assertClose(3.5, value("1.5 + 2"))
    }

    // --- Error results ----------------------------------------------

    @Test fun divisionByZeroIsReported() {
        assertEquals(Calculator.Result.DivByZero, Calculator.evaluate("5 / 0"))
    }

    @Test fun emptyIsUnparseable() {
        assertEquals(Calculator.Result.Unparseable, Calculator.evaluate("   "))
    }

    @Test fun trailingJunkIsUnparseable() {
        assertEquals(Calculator.Result.Unparseable, Calculator.evaluate("2 2"))
    }

    @Test fun danglingOperatorIsUnparseable() {
        assertEquals(Calculator.Result.Unparseable, Calculator.evaluate("2 +"))
    }

    @Test fun unbalancedParenIsUnparseable() {
        assertEquals(Calculator.Result.Unparseable, Calculator.evaluate("(2 + 3"))
    }
}
