package com.lazydevs.wristotle.handlers

/**
 * Tiny arithmetic evaluator for the [com.lazydevs.wristotle.speech.nlu.Intent.Calculate]
 * handler. Pure Kotlin — no Android, no `eval`, no dependency — so it's safe
 * (no code execution) and unit-tests directly on the host JVM.
 *
 * Accepts a **normalised** infix string of decimals, the four operators
 * `+ - * /`, and parentheses (whitespace ignored). Spoken forms ("plus",
 * "15% of 80") are turned into this shape upstream by `CalculateSlots`, so
 * the evaluator itself stays a plain expression parser.
 *
 * Grammar (recursive descent, standard precedence):
 *   expr   := term (('+' | '-') term)*
 *   term   := factor (('*' | '/') factor)*
 *   factor := number | '(' expr ')' | '-' factor
 */
object Calculator {

    sealed interface Result {
        data class Value(val number: Double) : Result
        /** Division by zero somewhere in the expression. */
        data object DivByZero : Result
        /** Empty, malformed, or contains unsupported tokens. */
        data object Unparseable : Result
    }

    fun evaluate(expression: String): Result {
        val tokens = tokenize(expression) ?: return Result.Unparseable
        if (tokens.isEmpty()) return Result.Unparseable
        return try {
            val parser = Parser(tokens)
            val value = parser.parseExpr()
            if (!parser.atEnd()) Result.Unparseable          // trailing junk, e.g. "2 2"
            else if (value.isNaN() || value.isInfinite()) Result.Unparseable
            else Result.Value(value)
        } catch (_: DivByZeroSignal) {
            Result.DivByZero
        } catch (_: ParseError) {
            Result.Unparseable
        }
    }

    // ── Tokenizer ───────────────────────────────────────────────────────────

    /** Splits into number / operator / paren tokens. Returns null on any
     *  unsupported character so the caller reports "couldn't work that out". */
    private fun tokenize(expr: String): List<String>? {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' -> {
                    val start = i
                    var dots = if (c == '.') 1 else 0
                    i++
                    while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) {
                        if (expr[i] == '.' && ++dots > 1) return null  // "1.2.3"
                        i++
                    }
                    tokens.add(expr.substring(start, i))
                }
                c == '+' || c == '-' || c == '*' || c == '/' || c == '(' || c == ')' -> {
                    tokens.add(c.toString()); i++
                }
                else -> return null
            }
        }
        return tokens
    }

    // ── Parser ──────────────────────────────────────────────────────────────

    private class ParseError : RuntimeException()
    private class DivByZeroSignal : RuntimeException()

    private class Parser(private val tokens: List<String>) {
        private var pos = 0

        fun atEnd(): Boolean = pos >= tokens.size

        private fun peek(): String? = tokens.getOrNull(pos)
        private fun next(): String = tokens.getOrNull(pos++) ?: throw ParseError()

        fun parseExpr(): Double {
            var acc = parseTerm()
            while (peek() == "+" || peek() == "-") {
                val op = next()
                val rhs = parseTerm()
                acc = if (op == "+") acc + rhs else acc - rhs
            }
            return acc
        }

        private fun parseTerm(): Double {
            var acc = parseFactor()
            while (peek() == "*" || peek() == "/") {
                val op = next()
                val rhs = parseFactor()
                if (op == "*") {
                    acc *= rhs
                } else {
                    if (rhs == 0.0) throw DivByZeroSignal()
                    acc /= rhs
                }
            }
            return acc
        }

        private fun parseFactor(): Double {
            val t = peek() ?: throw ParseError()
            return when {
                t == "-" -> { next(); -parseFactor() }   // unary minus
                t == "(" -> {
                    next()
                    val inner = parseExpr()
                    if (next() != ")") throw ParseError()
                    inner
                }
                else -> next().toDoubleOrNull() ?: throw ParseError()
            }
        }
    }
}
