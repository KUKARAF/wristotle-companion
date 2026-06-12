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
import com.lazydevs.wristotle.speech.nlu.handlers.DefaultTitles
import com.lazydevs.wristotle.speech.nlu.handlers.formatClock12h
import com.lazydevs.wristotle.speech.nlu.slots.SlotKeys
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.speech.nlu.instantSlot
import java.util.Calendar

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
        // SetAlarmSlots puts a kotlinx-datetime Instant here (not java.util.Date)
        // — casting to Date silently failed and every voice alarm reported
        // "couldn't understand" AFTER the confirm preview showed the right time.
        val instant = result.instantSlot(SlotKeys.Time)
            ?: return "Couldn't understand the time.\nTry \"set an alarm for 7am\"."

        val cal = Calendar.getInstance().apply { timeInMillis = instant.toEpochMilliseconds() }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val destination = settings.defaultDestination.value

        val draft = AlarmEntity(
            hour = hour,
            minute = minute,
            label = DefaultTitles.ALARM,
            destination = destination.name,
            wireEpoch = null,
            enabled = true,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        val id = repository.insert(draft)
        val saved = repository.getById(id) ?: draft.copy(id = id)
        val dispatchResult = dispatcher.schedule(saved)

        val clock = formatClock12h(hour, minute)
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

}