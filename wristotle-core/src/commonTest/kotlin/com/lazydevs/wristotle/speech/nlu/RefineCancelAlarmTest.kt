// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards [PrefixHints.refineCancelAlarm] — the deterministic Cancel →
 * CancelAlarm upgrade applied on the watch-hinted path (a watch "cancel" that
 * mentions an alarm must route to the alarm canceller, not the reminder one).
 * Previously only covered transitively by VoicePipeline.
 */
class RefineCancelAlarmTest {

    @Test
    fun cancelMentioningAlarmUpgrades() {
        assertEquals(Intent.CancelAlarm, PrefixHints.refineCancelAlarm("cancel the 7am alarm", Intent.Cancel))
        assertEquals(Intent.CancelAlarm, PrefixHints.refineCancelAlarm("stop the alarm", Intent.Cancel))
        assertEquals(Intent.CancelAlarm, PrefixHints.refineCancelAlarm("cancel all alarms", Intent.Cancel))
    }

    @Test
    fun cancelWithoutAlarmStaysCancel() {
        assertEquals(Intent.Cancel, PrefixHints.refineCancelAlarm("cancel my reminder", Intent.Cancel))
        assertEquals(Intent.Cancel, PrefixHints.refineCancelAlarm("cancel the meeting", Intent.Cancel))
    }

    @Test
    fun nonCancelIntentIsNeverTouched() {
        // Even with the word "alarm" present, a non-Cancel pick is left alone.
        assertEquals(Intent.SetAlarm, PrefixHints.refineCancelAlarm("set an alarm for 7am", Intent.SetAlarm))
        assertEquals(Intent.Reminder, PrefixHints.refineCancelAlarm("remind me about the alarm", Intent.Reminder))
    }

    @Test
    fun alarmMustBeAWholeWord() {
        // "alarming" is not "alarm" — the \balarms?\b marker must not match it.
        assertEquals(Intent.Cancel, PrefixHints.refineCancelAlarm("cancel the alarming noise", Intent.Cancel))
    }
}
