// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.handlers

import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.speech.nlu.handler.ActionHandler
import com.lazydevs.wristotle.speech.nlu.slots.calcExpression
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Handles [Intent.Calculate] — evaluates the arithmetic expression produced
 * by `CalculateSlots` via the pure [Calculator] and returns a watch-friendly
 * result ("= 12"). Whole numbers render without a decimal; fractional results
 * are rounded to 4 places with trailing zeros trimmed.
 *
 * Fails soft with a "Couldn't…" message (which the history badge treats as a
 * failure, keeping a bad parse out of NLU learning).
 *
 * R5 — lifted from :app. java.math.BigDecimal replaced with a manual
 * round-to-4-decimal-places + trailing-zero strip so commonMain has zero
 * JVM-only deps.
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
        // Round to 4 decimal places + strip trailing zeros.
        val scale = 10_000L
        val rounded = (d * scale).roundToLong()
        val sign = if (rounded < 0) "-" else ""
        val absVal = abs(rounded)
        val whole = absVal / scale
        val frac = absVal % scale
        if (frac == 0L) return "$sign$whole"
        val fracStr = frac.toString().padStart(4, '0').trimEnd('0')
        return "$sign$whole.$fracStr"
    }

    private companion object {
        const val PARSE_FAIL = "Couldn't work that out.\nTry \"what's 15% of 80\"."
    }
}
