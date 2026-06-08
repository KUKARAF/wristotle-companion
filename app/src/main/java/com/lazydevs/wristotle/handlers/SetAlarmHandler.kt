// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.speech.nlu.alarms.AlarmDestination
import com.lazydevs.wristotle.speech.nlu.settings.AlarmSettings
import com.lazydevs.wristotle.alarms.AlarmDispatcher
import com.lazydevs.wristotle.alarms.AlarmEntity
import com.lazydevs.wristotle.alarms.AlarmRepository
import com.lazydevs.wristotle.alarms.DispatchResult
import com.lazydevs.wristotle.speech.nlu.handler.ActionHandler
import com.lazydevs.wristotle.speech.nlu.slots.SlotKeys
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import java.util.Calendar
import java.util.Date

/**
 * Handles [Intent.SetAlarm] — creates a new alarm via the same Room +
 * dispatcher pipeline the companion UI's AlarmEditorDialog uses.
 *
 * Destination comes from [AlarmSettings.defaultDestination] (set in
 * the Alarms card; default Phone). Voice grammar doesn't carry an
 * explicit destination — the per-alarm choice lives in the UI. Users
 * who routinely speak alarms can flip the default once.
 *
 * Destructive — gated by the confirm-before-dispatch surface so a
 * misheard time ("set an alarm for seven" → "for eleven") gets a
 * one-line preview before the wakeup is scheduled.
 */
class SetAlarmHandler(
    private val repository: AlarmRepository,
    private val dispatcher: AlarmDispatcher,
    private val settings: AlarmSettings,
) : ActionHandler {

    override val tag: String = "set_alarm"
    override val intent: Intent = Intent.SetAlarm

    override suspend fun handle(result: IntentResult): String {
        val time = result.slots[SlotKeys.Time] as? Date
            ?: return "Couldn't understand the time.\nTry \"set an alarm for 7am\"."

        val cal = Calendar.getInstance().apply { this.time = time }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val destination = settings.defaultDestination.value

        val draft = AlarmEntity(
            hour = hour,
            minute = minute,
            label = "Alarm",
            destination = destination.name,
            wireEpoch = null,
            enabled = true,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        val id = repository.insert(draft)
        val saved = repository.getById(id) ?: draft.copy(id = id)
        val dispatchResult = dispatcher.schedule(saved)

        val clock = formatClock(hour, minute)
        val destLabel = destinationLabel(destination)
        return when (dispatchResult) {
            is DispatchResult.Success ->
                "Alarm set for $clock ($destLabel)."
            is DispatchResult.PartialFailure ->
                "Alarm saved for $clock ($destLabel), but: ${dispatchResult.errors.joinToString("; ")}"
        }
    }

    private fun destinationLabel(destination: AlarmDestination): String = when (destination) {
        AlarmDestination.Phone -> "phone"
        AlarmDestination.Watch -> "watch"
        AlarmDestination.Both  -> "phone + watch"
    }

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