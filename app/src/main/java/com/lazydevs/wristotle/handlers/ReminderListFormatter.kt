package com.lazydevs.wristotle.handlers

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Formats the pending-reminder list into a watch-friendly message. Pure (uses
 * `java.text`, not Android), so it's unit-testable with an injected `now`.
 *
 * Drops past-due reminders (their pin has already fired and can't be acted on)
 * and caps the visible rows so a long list doesn't blow past the watch chat's
 * display width — extras collapse into an "…and N more" line.
 */
object ReminderListFormatter {

    private const val MAX_SHOWN = 5

    fun format(records: List<ReminderRecord>, now: Long): String {
        val pending = records.filter { it.timeMs == null || it.timeMs >= now }
        if (pending.isEmpty()) return "You have no reminders"

        val fmt = SimpleDateFormat("MMM d 'at' h:mm a", Locale.getDefault())
        val lines = pending.take(MAX_SHOWN).joinToString("\n") { r ->
            val time = r.timeMs?.let { fmt.format(Date(it)) }
            if (time != null) "• ${r.title} — $time" else "• ${r.title}"
        }
        val header = if (pending.size == 1) "You have 1 reminder:" else "You have ${pending.size} reminders:"
        val more = if (pending.size > MAX_SHOWN) "\n…and ${pending.size - MAX_SHOWN} more" else ""
        return "$header\n$lines$more"
    }
}
