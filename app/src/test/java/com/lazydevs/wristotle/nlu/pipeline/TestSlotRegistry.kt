// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.nlu.pipeline

import com.lazydevs.wristotle.speech.nlu.slots.AddTaskSlots
import com.lazydevs.wristotle.speech.nlu.slots.*
import com.lazydevs.wristotle.speech.nlu.slots.AppendNoteSlots
import com.lazydevs.wristotle.speech.nlu.slots.AskAgentSlots
import com.lazydevs.wristotle.speech.nlu.slots.CalculateSlots
import com.lazydevs.wristotle.nlu.slots.CalendarSlots
import com.lazydevs.wristotle.speech.nlu.slots.CallSlots
import com.lazydevs.wristotle.speech.nlu.slots.CancelSlots
import com.lazydevs.wristotle.speech.nlu.slots.CompleteTaskSlots
import com.lazydevs.wristotle.nlu.slots.CreateEventSlots
import com.lazydevs.wristotle.speech.nlu.slots.FindPhoneSlots
import com.lazydevs.wristotle.nlu.slots.ListRemindersSlots
import com.lazydevs.wristotle.speech.nlu.slots.ListTasksSlots
import com.lazydevs.wristotle.speech.nlu.slots.MediaPlaySlots
import com.lazydevs.wristotle.speech.nlu.slots.MediaSeekSlots
import com.lazydevs.wristotle.speech.nlu.slots.MediaTargetSlots
import com.lazydevs.wristotle.speech.nlu.slots.NoteSlots
import com.lazydevs.wristotle.speech.nlu.slots.OpenAppSlots
import com.lazydevs.wristotle.nlu.slots.ReminderSlots
import com.lazydevs.wristotle.nlu.slots.RescheduleSlots
import com.lazydevs.wristotle.nlu.slots.SendMessageSlots
import com.lazydevs.wristotle.nlu.slots.CancelAlarmSlots
import com.lazydevs.wristotle.speech.nlu.slots.SetTimerSlots
import com.lazydevs.wristotle.speech.nlu.slots.WeatherSlots
import com.lazydevs.wristotle.speech.nlu.slots.WorldTimeSlots
import com.lazydevs.wristotle.phone.ContactsRepository
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractorRegistry

/**
 * Build a [SlotExtractorRegistry] for pipeline tests that mirrors the
 * production wiring in `WristotleApplication.onCreate` — same intent
 * mapping, same shared `mediaTargetSlots` / `mediaSeekSlots` instances —
 * but swaps the Android-dependent constructor args for cheap test fakes.
 *
 * The fakes:
 *  - [findContact] defaults to "no contact found" (returns null). Override
 *    to model resolved contacts for a Call / SendMessage test row.
 *  - [reminderOffsetMin] defaults to 30 (matches the `ReminderSettings`
 *    out-of-box value).
 *  - [askAgentSubjects] defaults to empty (no custom triggers). Override
 *    to test the AskAgent wake-word / verb-form pre-pass.
 *
 * If the production registry grows a new intent, this builder is the
 * canonical second site to update — keeping the two in sync is the whole
 * point of the harness.
 */
fun testSlotRegistry(
    findContact: suspend (String) -> ContactsRepository.Contact? = { null },
    reminderOffsetMin: () -> Int = { 30 },
    askAgentSubjects: () -> List<String> = { emptyList() },
): SlotExtractorRegistry {
    val mediaTargetSlots = MediaTargetSlots()
    val mediaSeekSlots = MediaSeekSlots()
    return SlotExtractorRegistry(mapOf(
        Intent.Call to CallSlots(),
        Intent.SendMessage to SendMessageSlots(findContact = findContact),
        Intent.Reminder to ReminderSlots(defaultOffsetMinProvider = reminderOffsetMin),
        Intent.Cancel to CancelSlots(),
        Intent.ListReminders to ListRemindersSlots(),
        Intent.Reschedule to RescheduleSlots(),
        Intent.FindPhone to FindPhoneSlots(),
        Intent.MediaPlay to MediaPlaySlots(),
        Intent.MediaPause to mediaTargetSlots,
        Intent.MediaNext to mediaTargetSlots,
        Intent.MediaPrevious to mediaTargetSlots,
        Intent.MediaSeekForward to mediaSeekSlots,
        Intent.MediaSeekBackward to mediaSeekSlots,
        Intent.OpenApp to OpenAppSlots(),
        Intent.Calendar to CalendarSlots(),
        Intent.CreateEvent to CreateEventSlots(),
        Intent.Note to NoteSlots(),
        Intent.AppendNote to AppendNoteSlots(),
        Intent.AddTask to AddTaskSlots(),
        Intent.ListTasks to ListTasksSlots(),
        Intent.CompleteTask to CompleteTaskSlots(),
        Intent.DeleteTask to CompleteTaskSlots(),
        Intent.CancelAlarm to CancelAlarmSlots(),
        Intent.SetTimer to SetTimerSlots(),
        Intent.WorldTime to WorldTimeSlots(),
        Intent.Calculate to CalculateSlots(),
        Intent.Weather to WeatherSlots(),
        Intent.AskAgent to AskAgentSlots(extrasProvider = askAgentSubjects),
    ))
}