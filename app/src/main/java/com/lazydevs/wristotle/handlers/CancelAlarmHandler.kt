// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.alarms.AlarmDestination
import com.lazydevs.wristotle.alarms.AlarmDispatcher
import com.lazydevs.wristotle.alarms.AlarmEntity
import com.lazydevs.wristotle.alarms.AlarmRepository
import com.lazydevs.wristotle.speech.nlu.handler.ActionHandler
import com.lazydevs.wristotle.speech.nlu.slots.SlotKeys
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.transport.PebbleTransport
import java.util.Calendar
import java.util.Date

/**
 * Handles [Intent.CancelAlarm].
 *
 * Two grammar shapes:
 *  - Bare "cancel alarm" / "stop the alarm" — no [SlotKeys.Time] slot.
 *    Cancels ALL pending watch alarms via [PebbleTransport.sendAlarmCancel] (0).
 *  - Time-qualified "cancel 7am alarm" — `time` slot present. Looks up
 *    Room rows whose `(hour, minute)` matches and cancels each watch leg
 *    by its `wireEpoch`. If multiple alarms share the time, all are cancelled.
 *
 * Phone-leg alarms can't be cancelled programmatically (the Android
 * AlarmClock dead-end documented in alarm-timer.md). The response text
 * surfaces that constraint when:
 *  - any candidate alarm has destination = Phone (no watch leg to
 *    cancel — pure no-op + advisory message)
 *  - any candidate alarm has destination = Both (watch leg cancels;
 *    phone leg untouched + advisory)
 *
 * Watch-side response (ALARM_CANCEL_RESULT) lands asynchronously and is
 * logged in the conversation history separately — we don't await it here.
 * The handler returns immediately with the destination-aware message
 * built from Room state so the user gets the explanation in the chat
 * bubble.
 *
 * Non-destructive — read-write but reversible (user re-enables in the
 * companion UI). Not gated by the confirm-before-dispatch surface.
 */
class CancelAlarmHandler(
    private val transport: PebbleTransport,
    private val repository: AlarmRepository,
    private val dispatcher: AlarmDispatcher,
) : ActionHandler {

    override val tag: String = "cancel_alarm"
    override val intent: Intent = Intent.CancelAlarm

    override suspend fun handle(result: IntentResult): String {
        val time = result.slots[SlotKeys.Time] as? Date
        return if (time == null) {
            cancelAll()
        } else {
            val cal = Calendar.getInstance().apply { this.time = time }
            cancelAtTime(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        }
    }

    private suspend fun cancelAll(): String {
        val active = repository.getAll().filter { it.enabled }

        // Send the cancel-all to the watch regardless — even if Room is
        // empty, the watch might still have legacy slots from a previous
        // install. The watch handles a "no alarms to cancel" response
        // gracefully.
        runCatching { transport.sendAlarmCancel(0) }
        // Cancel = delete in v1 (one-shot alarms; no recurring means
        // disabling a row to re-arm later has no use case). The row
        // disappears from the list; if it's a Both alarm, the response
        // text warns the phone leg is still pending.
        active.forEach { repository.delete(it.id) }

        // Voice cancel always targets the watch leg — phone alarms are a
        // programmatic dead-end (Android AlarmClock dead-end). The
        // response talks about watch alarms only, and only mentions the
        // phone leg when a Both alarm was actually cancelled (because
        // its phone leg is still pending in the clock app).
        val bothHits  = active.count { destOf(it) == AlarmDestination.Both }
        val watchHits = active.count { destOf(it) == AlarmDestination.Watch }
        val watchCancels = watchHits + bothHits

        return when {
            bothHits > 0 ->
                "Watch alarm cancelled.\nPhone alarm still pending."
            watchCancels > 0 ->
                if (watchCancels == 1) "Watch alarm cancelled."
                else                   "Watch alarms cancelled."
            else ->
                "No watch alarms to cancel."
        }
    }

    private suspend fun cancelAtTime(hour: Int, minute: Int): String {
        val matches = repository.getByHourMinute(hour, minute).filter { it.enabled }
        if (matches.isEmpty()) {
            return "No alarm at ${formatClock(hour, minute)}."
        }

        var watchCancels = 0
        var bothCancels = 0
        for (alarm in matches) {
            val dest = destOf(alarm)
            if (dest == AlarmDestination.Watch || dest == AlarmDestination.Both) {
                if (dispatcher.cancelWatchLeg(alarm)) {
                    watchCancels++
                    if (dest == AlarmDestination.Both) bothCancels++
                }
            }
            // Cancel = delete (one-shot alarms; no recurring re-arm).
            repository.delete(alarm.id)
        }

        val time = formatClock(hour, minute)
        return when {
            bothCancels > 0 ->
                "Watch alarm at $time cancelled.\nPhone alarm still pending."
            watchCancels > 0 ->
                "Watch alarm at $time cancelled."
            else ->
                "No watch alarm at $time."
        }
    }

    private fun destOf(alarm: AlarmEntity): AlarmDestination =
        runCatching { AlarmDestination.valueOf(alarm.destination) }
            .getOrElse { AlarmDestination.Phone }

    private fun formatClock(hour: Int, minute: Int): String {
        val period = if (hour < 12) "AM" else "PM"
        val h12 = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return "%d:%02d %s".format(h12, minute, period)
    }
}