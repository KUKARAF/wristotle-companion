package com.lazydevs.wristotle.briefing

import com.lazydevs.wristotle.alarms.AlarmEntity
import com.lazydevs.wristotle.handlers.ReminderRecord
import com.lazydevs.wristotle.notes.Note
import com.lazydevs.wristotle.phone.CalendarRepository
import com.lazydevs.wristotle.tasks.TaskEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure formatting layer for the Morning Brief. Each `*Section` function
 * takes an already-filtered entity list (the handler owns the IO + the
 * today-range filter) and returns either a one-line summary string OR
 * `null` when the section has nothing to surface.
 *
 * No Android imports — unit-testable as plain JUnit.
 *
 * Each line is intentionally short. Pebble watches render the brief on
 * the chat surface with the 280-char cap from chat_ui; if the join goes
 * over, the handler drops sections from the END so the high-priority
 * stuff (calendar → alarms → reminders → tasks → notes) stays visible.
 *
 * No emoji on the prefix — Pebble's system GOTHIC font is ~Latin-1
 * only so emoji renders as a fallback rectangle on Aplite / Diorite.
 * Word prefixes ("Meetings:", "Alarms:", …) read cleanly everywhere.
 */
object MorningBriefRenderer {

    /** Returned when no section has data. The handler turns this into the
     *  outer response ("Nothing planned for today."). Surface as a constant
     *  so the user-facing string lives next to the rendering code. */
    const val EMPTY_BRIEF: String = "Nothing planned for today."

    /**
     * Compose the brief from already-fetched section inputs. Drops
     * any null sections (empty inputs). Returns [EMPTY_BRIEF] when
     * every section is empty.
     */
    fun render(sections: List<String?>): String {
        val nonEmpty = sections.filterNotNull()
        if (nonEmpty.isEmpty()) return EMPTY_BRIEF
        return nonEmpty.joinToString(separator = "\n")
    }

    /**
     * "Meetings: N today: title @ HH:MM, title @ HH:MM, …" — capped to
     * the first three by start time so a packed day doesn't blow the
     * watch budget. The fourth-and-beyond count rolls into a trailing
     * "+N more" hint.
     */
    fun meetingsSection(events: List<CalendarRepository.Event>): String? {
        if (events.isEmpty()) return null
        val total = events.size
        val preview = events.sortedBy { it.begin }.take(MEETINGS_PREVIEW)
        val joined = preview.joinToString(", ") { e ->
            val time = formatTime(e.begin)
            val title = e.title.ifBlank { "Untitled" }
            "$title @ $time"
        }
        val extra = total - preview.size
        val tail = if (extra > 0) ", +$extra more" else ""
        return if (total == 1) "Meetings: 1 today — $joined$tail"
        else                   "Meetings: $total today — $joined$tail"
    }

    /**
     * "Alarms: 1 at 7:00am" (single) or "Alarms: 3 today — 6:30am,
     * 7am, 8am" (multi, first three).
     */
    fun alarmsSection(alarms: List<AlarmEntity>): String? {
        if (alarms.isEmpty()) return null
        val sorted = alarms.sortedWith(compareBy({ it.hour }, { it.minute }))
        if (sorted.size == 1) {
            val a = sorted.first()
            return "Alarms: 1 at ${formatHourMinute(a.hour, a.minute)}"
        }
        val preview = sorted.take(ALARMS_PREVIEW)
            .joinToString(", ") { formatHourMinute(it.hour, it.minute) }
        val extra = sorted.size - ALARMS_PREVIEW
        val tail = if (extra > 0) ", +$extra more" else ""
        return "Alarms: ${sorted.size} today — $preview$tail"
    }

    /**
     * "Reminders: 2 due today" — count-only for v1. Reminder titles are
     * often long ("remind me to pick up dry cleaning before 5pm") and
     * compose with the existing watch reminder pin, so the brief
     * deliberately points the user at that surface instead of inlining
     * every body.
     */
    fun remindersSection(reminders: List<ReminderRecord>): String? {
        if (reminders.isEmpty()) return null
        return if (reminders.size == 1) "Reminders: 1 due today"
        else                            "Reminders: ${reminders.size} due today"
    }

    /**
     * "Tasks: 4 pending" — count-only. Listing pending task bodies
     * inline would dominate the brief; the user has voice
     * `"list my tasks"` for the full view.
     */
    fun tasksSection(pending: List<TaskEntity>): String? {
        if (pending.isEmpty()) return null
        return "Tasks: ${pending.size} pending"
    }

    /**
     * Notes added today. Singular preview shows the body trimmed to one
     * sentence; multiple → count + most-recent body.
     */
    fun notesSection(todayNotes: List<Note>): String? {
        if (todayNotes.isEmpty()) return null
        val newest = todayNotes.maxByOrNull { it.createdAtEpochMs } ?: return null
        val preview = previewSnippet(newest.body)
        return if (todayNotes.size == 1) "Notes: 1 today — \"$preview\""
        else                              "Notes: ${todayNotes.size} today, latest \"$preview\""
    }

    /** Truncate a multi-line / long note body to a single readable line for
     *  the brief. Caps at [NOTE_PREVIEW_CHARS] including an ellipsis. */
    internal fun previewSnippet(body: String): String {
        val firstLine = body.lineSequence().firstOrNull()?.trim().orEmpty()
        return if (firstLine.length <= NOTE_PREVIEW_CHARS) firstLine
               else firstLine.take(NOTE_PREVIEW_CHARS - 1) + "…"
    }

    private fun formatTime(epochMs: Long): String =
        TIME_FORMAT.get()!!.format(Date(epochMs)).lowercase(Locale.ROOT)

    private fun formatHourMinute(hour: Int, minute: Int): String {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
        }
        return TIME_FORMAT.get()!!.format(cal.time).lowercase(Locale.ROOT)
    }

    private const val MEETINGS_PREVIEW = 3
    private const val ALARMS_PREVIEW = 3
    private const val NOTE_PREVIEW_CHARS = 40

    // SimpleDateFormat isn't thread-safe; a ThreadLocal lets the renderer
    // stay an `object` without forcing every call site to allocate one.
    private val TIME_FORMAT: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("h:mma", Locale.US)
    }
}
