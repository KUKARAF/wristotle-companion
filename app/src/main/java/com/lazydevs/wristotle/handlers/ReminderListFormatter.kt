// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.speech.nlu.reminders.PinStoreCodec
import com.lazydevs.wristotle.speech.nlu.reminders.ReminderRecord
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
            val t = it.timeMs ?: return@filter false
            PinStoreCodec.isActive(t, now) && localHour(t) == targetHour
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
            val tail = if (r.isPersistent) " (persistent)" else ""
            if (time != null) "• ${r.title} — $time$tail" else "• ${r.title}$tail"
        }
    }

    private fun localHour(ms: Long): Int =
        Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.HOUR_OF_DAY)
}