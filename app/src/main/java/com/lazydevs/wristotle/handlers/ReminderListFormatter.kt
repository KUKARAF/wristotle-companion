package com.lazydevs.wristotle.handlers

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Formats the pending-reminder list into a watch-friendly message. Pure (uses
 * `java.text` / `java.util`, not Android), so it's unit-testable with an
 * injected `now`.
 *
 * Drops past-due reminders (their pin has already fired and can't be acted on)
 * and caps the visible rows so a long list doesn't blow past the watch chat's
 * display width — extras collapse into an "…and N more" line.
 */
object ReminderListFormatter {

    private const val MAX_SHOWN = 5

    /** Full active list (upcoming + fired within the retention window), newest-first. */
    fun format(records: List<ReminderRecord>, now: Long): String {
        val pending = records.filter { PinStoreCodec.isActive(it.timeMs, now) }
        if (pending.isEmpty()) return "You have no reminders"
        return "${countHeader(pending.size)}\n${rows(pending)}" +
            if (pending.size > MAX_SHOWN) "\n…and ${pending.size - MAX_SHOWN} more" else ""
    }

    /**
     * Answer for a specific clock time ("is there a reminder at 2pm"). Matches
     * pending reminders in the same local hour as [targetMs] (minute-level
     * precision is overkill for voice). Reports the matches, or says there's
     * nothing at that time — never dumps the whole list.
     */
    fun formatAtTime(records: List<ReminderRecord>, targetMs: Long, now: Long): String {
        val targetHour = localHour(targetMs)
        val label = SimpleDateFormat("h a", Locale.getDefault()).format(Date(targetMs)) // "2 PM"
        val matches = records.filter {
            it.timeMs != null && PinStoreCodec.isActive(it.timeMs, now) && localHour(it.timeMs) == targetHour
        }
        if (matches.isEmpty()) return "No reminder around $label"
        val header = if (matches.size == 1) "1 reminder around $label:" else "${matches.size} reminders around $label:"
        return "$header\n${rows(matches)}"
    }

    private fun countHeader(n: Int) = if (n == 1) "You have 1 reminder:" else "You have $n reminders:"

    private fun rows(records: List<ReminderRecord>): String {
        val fmt = SimpleDateFormat("MMM d 'at' h:mm a", Locale.getDefault())
        return records.take(MAX_SHOWN).joinToString("\n") { r ->
            val time = r.timeMs?.let { fmt.format(Date(it)) }
            if (time != null) "• ${r.title} — $time" else "• ${r.title}"
        }
    }

    private fun localHour(ms: Long): Int =
        Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.HOUR_OF_DAY)
}
