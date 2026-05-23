package com.lazydevs.wristotle.handlers

import android.content.Context

/**
 * SharedPreferences-backed ring buffer of recent reminders, newest-first.
 *
 * Stores [ReminderRecord]s (id + title + time), not just pin ids, so callers
 * can list pending reminders and target a specific one for cancel / reschedule
 * — the watch timeline API offers no "list pins" query, so this is the only
 * place that knows *what* each pending reminder is.
 *
 * All mutating operations are guarded by an intrinsic lock so concurrent
 * reminders dispatched from different coroutines can't race on the underlying
 * read-modify-write of the prefs string. Reads are also locked so callers see
 * a value consistent with the most recent write. Serialization (and migration
 * from the old id-only format) lives in [PinStoreCodec].
 */
class PinStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun save(record: ReminderRecord) = synchronized(lock) {
        val records = load().toMutableList()
        records.removeAll { it.id == record.id }
        records.add(0, record)
        if (records.size > MAX_PINS) records.subList(MAX_PINS, records.size).clear()
        persist(records)
    }

    fun remove(pinId: String) = synchronized(lock) {
        val records = load().toMutableList()
        if (records.removeAll { it.id == pinId }) persist(records)
    }

    /** All pending reminders, newest-first. */
    fun all(): List<ReminderRecord> = synchronized(lock) { load() }

    /**
     * Bulk replace — used by the backup importer to commit a merged list in
     * one shot. Trims to [MAX_PINS] (FIFO) and persists. Order is preserved
     * (caller's responsibility — typically newest-first).
     */
    fun replaceAll(records: List<ReminderRecord>) = synchronized(lock) {
        val trimmed = if (records.size > MAX_PINS) records.subList(0, MAX_PINS) else records
        persist(trimmed.toList())
    }

    /** The most-recently-added reminder, or null if none. */
    fun latest(): ReminderRecord? = synchronized(lock) { load().firstOrNull() }

    /**
     * Decodes the stored records and prunes any that have already fired,
     * persisting the trimmed list so past-due reminders don't accumulate. All
     * public accessors go through here, so a fired reminder is dropped on the
     * next touch of the store.
     */
    private fun load(): List<ReminderRecord> {
        val stored = PinStoreCodec.decode(prefs.getString(KEY_IDS, "") ?: "")
        val pending = PinStoreCodec.prunePastDue(stored, System.currentTimeMillis())
        if (pending.size != stored.size) persist(pending)
        return pending
    }

    private fun persist(records: List<ReminderRecord>) {
        prefs.edit().putString(KEY_IDS, PinStoreCodec.encode(records)).apply()
    }

    private companion object {
        const val PREFS_NAME = "reminder_pins"
        const val KEY_IDS = "pin_ids"
        const val MAX_PINS = 10
    }
}
