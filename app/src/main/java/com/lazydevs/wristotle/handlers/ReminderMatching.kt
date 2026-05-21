package com.lazydevs.wristotle.handlers

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Matches a spoken target ("the gym one", "my 5pm") against the pending
 * reminders, so cancel / reschedule can act on a *specific* reminder instead
 * of blindly the latest. Pure + unit-testable.
 *
 * Deliberately conservative: a named target that matches nothing returns null
 * so the caller can say "no reminder matching that" rather than silently
 * acting on the wrong one — the same not-found-beats-wrong-guess principle as
 * the contact-match floor.
 */
object ReminderMatching {

    /** A target must clear this to count as a match. Single-token targets are
     *  all-or-nothing; a two-token target where only one token lands (0.5)
     *  falls below, so we don't cancel the wrong reminder on a partial hit. */
    const val MATCH_FLOOR = 0.6f

    /** Best pending (not past-due) reminder for [target], or null if none clears
     *  the floor. [now] is epoch-millis; past-due reminders are excluded. */
    fun bestMatch(target: String, records: List<ReminderRecord>, now: Long): ReminderRecord? {
        val t = normalize(target)
        if (t.isEmpty()) return null
        val pending = records.filter { it.timeMs == null || it.timeMs >= now }
        val best = pending.maxByOrNull { score(t, it) } ?: return null
        return if (score(t, best) >= MATCH_FLOOR) best else null
    }

    /** Score of [normalizedTarget] against [record], 0f..1f — the better of a
     *  title-token match and a clock-time match. */
    internal fun score(normalizedTarget: String, record: ReminderRecord): Float {
        val titleScore = tokenScore(normalizedTarget, normalize(record.title))
        val timeScore = record.timeMs?.let { timeScore(normalizedTarget, it) } ?: 0f
        return maxOf(titleScore, timeScore)
    }

    /** Fraction of target tokens that prefix-match a title token. */
    private fun tokenScore(target: String, title: String): Float {
        val targetTokens = target.split(' ').filter { it.isNotEmpty() }
        val titleTokens = title.split(' ').filter { it.isNotEmpty() }
        if (targetTokens.isEmpty() || titleTokens.isEmpty()) return 0f
        val matched = targetTokens.count { q ->
            titleTokens.any { it == q || it.startsWith(q) || q.startsWith(it) }
        }
        return matched.toFloat() / targetTokens.size
    }

    /** 1f when the target names this reminder's clock time ("5pm", "5 pm",
     *  "5:00pm"), else 0f. */
    private fun timeScore(target: String, timeMs: Long): Float {
        val compact = SimpleDateFormat("ha", Locale.US).format(Date(timeMs)).lowercase()      // "5pm"
        val withMinutes = SimpleDateFormat("h:mma", Locale.US).format(Date(timeMs)).lowercase() // "5:00pm"
        val tc = target.replace(" ", "")
        if (tc.length < 2) return 0f
        return if (tc == compact || tc == withMinutes || compact.contains(tc) || withMinutes.startsWith(tc)) 1f else 0f
    }

    private val MULTI_WS = Regex("\\s+")
    private fun normalize(s: String): String = s.trim().lowercase().replace(MULTI_WS, " ")
}
