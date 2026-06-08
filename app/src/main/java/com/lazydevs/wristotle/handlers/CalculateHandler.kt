// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.speech.nlu.slots.calcExpression
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor

/**
 * Handles [Intent.Calculate] — evaluates the arithmetic expression produced by
 * `CalculateSlots` via the pure [Calculator] and returns a watch-friendly
 * result ("= 12"). Whole numbers render without a decimal; fractional results
 * are rounded to 4 places with trailing zeros trimmed.
 *
 * Fails soft with a "Couldn't…" message (which the history badge treats as a
 * failure, keeping a bad parse out of NLU learning).
 */
class CalculateHandler : ActionHandler {

    override val tag: String = "calculate"
    override val intent: Intent = Intent.Calculate

    override suspend fun handle(result: IntentResult): String {
        val expression = result.slots.calcExpression()
            ?: return PARSE_FAIL
        return when (val r = Calculator.evaluate(expression)) {
            is Calculator.Result.Value -> "= ${formatNumber(r.number)}"
            Calculator.Result.DivByZero -> "Couldn't divide by zero."
            Calculator.Result.Unparseable -> PARSE_FAIL
        }
    }

    private fun formatNumber(d: Double): String {
        // Whole number → no decimal point. The magnitude guard keeps very
        // large doubles off the Long path (where they'd overflow/round oddly).
        if (d == floor(d) && abs(d) < 1e15) return d.toLong().toString()
        return BigDecimal.valueOf(d)
            .setScale(4, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    }

    private companion object {
        const val PARSE_FAIL = "Couldn't work that out.\nTry \"what's 15% of 80\"."
    }
}