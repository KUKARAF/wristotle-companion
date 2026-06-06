package com.lazydevs.wristotle.handlers

/**
 * One scheduled reminder we've inserted as a Pebble timeline pin.
 *
 * [title] and [timeMs] let us list reminders and target a specific one for
 * cancel / reschedule — the watch's timeline API is insert/delete-by-id only,
 * with no "list pins" query, so this local record is the sole source of truth
 * for *what* a pending reminder is.
 *
 * [timeMs] is the epoch-millis start time, or `null` when unknown (legacy pins
 * migrated from the old id-only format, which didn't store the time).
 *
 * [isPersistent] marks the reminder as one that should also nag the phone via
 * a notification at user-configured intervals after the watch pin fires (phase
 * B wires the scheduler). [attemptsRemaining] tracks how many more times the
 * scheduler is allowed to re-fire — defaults to 0 for non-persistent records.
 */
data class ReminderRecord(
    val id: String,
    val title: String,
    val timeMs: Long?,
    val isPersistent: Boolean = false,
    val attemptsRemaining: Int = 0,
)

/**
 * Pure (de)serialization for [PinStore], split out so it's unit-testable
 * without an Android `Context` (house style: pure JUnit, no Robolectric).
 *
 * Wire format is one record per line, fields separated by ASCII control chars
 * that dictated titles never contain (unit-separator U+001F between fields,
 * record-separator U+001E between records). This sidesteps escaping — a comma
 * or colon in a title (e.g. "buy milk, eggs") round-trips intact, which the
 * old comma-joined format could not have done.
 */
internal object PinStoreCodec {

    private const val RS = ''   // record separator
    private const val US = ''   // field separator

    fun encode(records: List<ReminderRecord>): String =
        records.joinToString(RS.toString()) { r ->
            // Defensively strip the separators from the title so a pathological
            // value can't corrupt the framing.
            val safeTitle = r.title.replace(RS, ' ').replace(US, ' ')
            listOf(
                r.id,
                safeTitle,
                r.timeMs?.toString() ?: "",
                if (r.isPersistent) "1" else "0",
                r.attemptsRemaining.toString(),
            ).joinToString(US.toString())
        }

    /**
     * Decodes [raw]. Handles two formats:
     *  - the current RS/US-framed records, and
     *  - the **legacy** format (a plain comma-joined list of bare pin ids),
     *    migrated to records with a placeholder title and unknown time so an
     *    in-flight reminder survives the upgrade.
     */
    fun decode(raw: String): List<ReminderRecord> {
        if (raw.isEmpty()) return emptyList()

        // Legacy: no field separators present → comma-joined ids.
        if (!raw.contains(US) && !raw.contains(RS)) {
            return raw.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { ReminderRecord(id = it, title = LEGACY_TITLE, timeMs = null) }
        }

        return raw.split(RS).mapNotNull { line ->
            if (line.isEmpty()) return@mapNotNull null
            val parts = line.split(US)
            val id = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = parts.getOrNull(1).orEmpty()
            val timeMs = parts.getOrNull(2)?.toLongOrNull()
            // Fields 4 + 5 added when persistent reminders shipped — absent in
            // older blobs, in which case both default to "not persistent".
            val isPersistent = parts.getOrNull(3) == "1"
            val attemptsRemaining = parts.getOrNull(4)?.toIntOrNull() ?: 0
            ReminderRecord(id, title, timeMs, isPersistent, attemptsRemaining)
        }
    }

    /**
     * How long a fired reminder stays "active" — listed, matchable, and kept in
     * the store — after its scheduled time. A reminder you set for 2pm is still
     * answerable ("is there a reminder at 2pm") later that day; only after a
     * full day does it get pruned. Keeps the store from accumulating stale rows
     * without yanking just-fired reminders out from under the user.
     */
    const val RETENTION_WINDOW_MS = 24L * 60 * 60 * 1000

    /**
     * A reminder is active if its time hasn't passed by more than
     * [RETENTION_WINDOW_MS]. Unknown-time records ([timeMs] null, e.g. legacy
     * migrations) are always active — we can't prove they're old. Single source
     * of truth for the pending/active filter used by the store, the list
     * formatter, and the matcher.
     */
    fun isActive(timeMs: Long?, now: Long): Boolean =
        timeMs == null || timeMs >= now - RETENTION_WINDOW_MS

    /**
     * Drops records that have been past-due for more than [RETENTION_WINDOW_MS].
     * Pure; the store calls this on every load so old reminders don't accumulate.
     */
    fun prunePastDue(records: List<ReminderRecord>, now: Long): List<ReminderRecord> =
        records.filter { isActive(it.timeMs, now) }

    const val LEGACY_TITLE = "Reminder"
}
