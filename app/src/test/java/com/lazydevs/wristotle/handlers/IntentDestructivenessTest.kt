package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.speech.nlu.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drift-detector test for the confirm-before-dispatch destructive set. Adding
 * a new value to [Intent] must explicitly choose a side; the when-expression
 * inside [requiresConfirm] is exhaustive, so this test merely formalises the
 * intent (pun unavoidable) and gives a single place to review whether a new
 * intent should require a confirm prompt or not.
 */
class IntentDestructivenessTest {

    @Test fun destructiveSetMatchesPlan() {
        val expectedDestructive = setOf(
            Intent.Call,
            Intent.SendMessage, // Covers SMS + WhatsApp + Telegram + Signal
            //                    after Phase A3 collapsed Intent.Sms into it.
            Intent.Reminder,
            Intent.Cancel,
            Intent.Reschedule,
            Intent.CreateEvent,
            Intent.OpenApp,
            Intent.MediaPlay,
        )
        for (intent in Intent.entries) {
            val expected = intent in expectedDestructive
            assertEquals(
                "Intent.$intent should${if (expected) "" else " NOT"} require confirm",
                expected,
                intent.requiresConfirm(),
            )
        }
    }

    @Test fun readOnlyIntentsDoNotConfirm() {
        // Spot-check the read-only set — these MUST stay false even when
        // the user has the confirm toggle on, otherwise basic local commands
        // become annoying.
        assertFalse(Intent.Time.requiresConfirm())
        assertFalse(Intent.Battery.requiresConfirm())
        assertFalse(Intent.Steps.requiresConfirm())
        assertFalse(Intent.FindPhone.requiresConfirm())
        assertFalse(Intent.ListReminders.requiresConfirm())
        assertFalse(Intent.Calendar.requiresConfirm())
        assertFalse(Intent.Unknown.requiresConfirm())
    }

    @Test fun mediaTogglesDoNotConfirm() {
        // Pause/Next/Previous/Seek aren't destructive in the user-data
        // sense — they just toggle music. Only MediaPlay confirms (it
        // launches/focuses an app).
        assertTrue(Intent.MediaPlay.requiresConfirm())
        assertFalse(Intent.MediaPause.requiresConfirm())
        assertFalse(Intent.MediaPlayPause.requiresConfirm())
        assertFalse(Intent.MediaNext.requiresConfirm())
        assertFalse(Intent.MediaPrevious.requiresConfirm())
        assertFalse(Intent.MediaSeekForward.requiresConfirm())
        assertFalse(Intent.MediaSeekBackward.requiresConfirm())
    }

    @Test fun notesAreNotDestructive() {
        // Notes are personal data, undo-by-delete is cheap, often dictated
        // in bursts. Confirm-gating each one would be annoying.
        assertFalse(Intent.Note.requiresConfirm())
        assertFalse(Intent.AppendNote.requiresConfirm())
    }
}
