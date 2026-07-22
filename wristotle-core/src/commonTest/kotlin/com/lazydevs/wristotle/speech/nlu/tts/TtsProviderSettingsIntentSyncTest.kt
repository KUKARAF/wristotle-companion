// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.tts

import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.settings.TtsProviderSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards against the audit's noted drift risk: `TtsProviderSettings` declares
 * its intent identifiers as hardcoded string literals (`"AskAgent"`,
 * `"Weather"`, …). The dispatch hook compares `routed.intent.name` against
 * those constants, so a rename of `Intent.AskAgent` → `Intent.AskAgentQuery`
 * would silently break the per-intent gating without a compiler error.
 *
 * This test asserts each constant equals the corresponding [Intent] enum
 * name — a rename of either side fails the build instead of a silent
 * dispatch regression.
 */
class TtsProviderSettingsIntentSyncTest {

    @Test fun `every TtsProviderSettings INTENT constant matches an Intent enum name`() {
        assertEquals(Intent.AskAgent.name,      TtsProviderSettings.INTENT_ASK_AGENT)
        assertEquals(Intent.HomeAssistant.name, TtsProviderSettings.INTENT_HOME_ASSISTANT)
        assertEquals(Intent.MorningBrief.name,  TtsProviderSettings.INTENT_MORNING_BRIEF)
        assertEquals(Intent.Weather.name,       TtsProviderSettings.INTENT_WEATHER)
        assertEquals(Intent.WorldTime.name,     TtsProviderSettings.INTENT_WORLD_TIME)
        assertEquals(Intent.Time.name,          TtsProviderSettings.INTENT_TIME)
        assertEquals(Intent.Battery.name,       TtsProviderSettings.INTENT_BATTERY)
        assertEquals(Intent.Calculate.name,     TtsProviderSettings.INTENT_CALCULATE)
        assertEquals(Intent.Call.name,          TtsProviderSettings.INTENT_CALL)
        assertEquals(Intent.SendMessage.name,   TtsProviderSettings.INTENT_SEND_MESSAGE)
        assertEquals(Intent.Reminder.name,      TtsProviderSettings.INTENT_REMINDER)
        assertEquals(Intent.ListReminders.name, TtsProviderSettings.INTENT_LIST_REMINDERS)
        assertEquals(Intent.Cancel.name,        TtsProviderSettings.INTENT_CANCEL)
        assertEquals(Intent.Reschedule.name,    TtsProviderSettings.INTENT_RESCHEDULE)
        assertEquals(Intent.SetAlarm.name,      TtsProviderSettings.INTENT_SET_ALARM)
        assertEquals(Intent.CancelAlarm.name,   TtsProviderSettings.INTENT_CANCEL_ALARM)
        assertEquals(Intent.SetTimer.name,      TtsProviderSettings.INTENT_SET_TIMER)
        assertEquals(Intent.Calendar.name,      TtsProviderSettings.INTENT_CALENDAR)
        assertEquals(Intent.CreateEvent.name,   TtsProviderSettings.INTENT_CREATE_EVENT)
        assertEquals(Intent.Note.name,          TtsProviderSettings.INTENT_NOTE)
        assertEquals(Intent.AppendNote.name,    TtsProviderSettings.INTENT_APPEND_NOTE)
        assertEquals(Intent.AddTask.name,       TtsProviderSettings.INTENT_ADD_TASK)
        assertEquals(Intent.ListTasks.name,     TtsProviderSettings.INTENT_LIST_TASKS)
        assertEquals(Intent.CompleteTask.name,  TtsProviderSettings.INTENT_COMPLETE_TASK)
        assertEquals(Intent.DeleteTask.name,    TtsProviderSettings.INTENT_DELETE_TASK)
        assertEquals(Intent.ShowCode.name,      TtsProviderSettings.INTENT_SHOW_CODE)
    }
}
