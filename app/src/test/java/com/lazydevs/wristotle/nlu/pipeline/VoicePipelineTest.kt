package com.lazydevs.wristotle.nlu.pipeline

import com.lazydevs.wristotle.nlu.VoicePipeline
import com.lazydevs.wristotle.nlu.slots.SlotKeys
import com.lazydevs.wristotle.speech.nlu.Intent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end voice routing tests — query in, (intent, slots) out, no
 * Android / PebbleKit / Whisper in the loop. Each row exercises the
 * full classifier → [com.lazydevs.wristotle.nlu.WatchHintRefiner] →
 * slot-extractor seam. See `tests.md` for how to add a row.
 */
class VoicePipelineTest {

    private fun route(
        query: String,
        watchHint: Intent? = null,
        classifier: FakeIntentClassifier = FakeIntentClassifier(),
        askAgentSubjects: List<String> = emptyList(),
    ): VoicePipeline.Routed = runBlocking {
        val subjectsProvider = { askAgentSubjects }
        VoicePipeline(
            classifier = classifier,
            slotExtractors = testSlotRegistry(askAgentSubjects = subjectsProvider),
            askAgentSubjects = subjectsProvider,
        ).route(query, watchHint)
    }

    // ── AskAgent routing — default verb form ───────────────────────────

    @Test fun `default verb form routes to AskAgent and strips lead-in`() {
        val r = route("ask agent what's the weather")
        assertEquals(Intent.AskAgent, r.result.intent)
        assertEquals("what's the weather", r.result.slots[SlotKeys.Query])
    }

    @Test fun `default hey form routes to AskAgent`() {
        val r = route("hey claude what time is it")
        assertEquals(Intent.AskAgent, r.result.intent)
        assertEquals("what time is it", r.result.slots[SlotKeys.Query])
    }

    // ── AskAgent routing — custom triggers ─────────────────────────────

    @Test fun `custom subject routes via verb form`() {
        val r = route("ask jarvis what's the weather", askAgentSubjects = listOf("jarvis"))
        assertEquals(Intent.AskAgent, r.result.intent)
        assertEquals("what's the weather", r.result.slots[SlotKeys.Query])
    }

    @Test fun `custom subject routes via wake-word form`() {
        // The v0.16.0 regression that prompted this test row — bare
        // "Jarvis …" must route AND strip in lockstep.
        val r = route(
            "Jarvis what is my github profile?",
            askAgentSubjects = listOf("jarvis"),
        )
        assertEquals(Intent.AskAgent, r.result.intent)
        assertEquals("what is my github profile?", r.result.slots[SlotKeys.Query])
    }

    @Test fun `bare built-in does not wake-word match`() {
        // "agent" alone is too generic to bare-match — and we have NO
        // custom subjects configured, so wake-word form should not fire.
        val r = route("agent fix my bug")
        assertNotEquals(Intent.AskAgent, r.result.intent)
    }

    // ── Ordering: AskAgent above Reminder ──────────────────────────────

    @Test fun `ask agent remind me beats Reminder route`() {
        // PrefixHints rule sits ABOVE Reminder so the lead-in wins even
        // when the body contains "remind". Otherwise the watch's
        // REMINDER_QUERY hint would steer this away.
        val r = route("ask agent remind me at 5pm to call mom")
        assertEquals(Intent.AskAgent, r.result.intent)
    }

    // ── Ordering: AddTask above CreateEvent (the "call" gotcha) ────────

    @Test fun `add task with call noun routes to AddTask not CreateEvent`() {
        // "call" is in CreateEvent's noun set. AddTask sits ABOVE
        // CreateEvent in PrefixHints so the task prefix wins.
        val r = route("add task call mom")
        assertEquals(Intent.AddTask, r.result.intent)
    }

    @Test fun `schedule meeting routes to CreateEvent`() {
        // Same family, opposite direction — once the AddTask prefix is
        // absent, CreateEvent's schedule+noun rule must still fire.
        val r = route("schedule a meeting with Alex tomorrow at 3pm")
        assertEquals(Intent.CreateEvent, r.result.intent)
    }

    // ── Watch-hint refinement (the REMINDER_FAMILY ladder) ─────────────

    @Test fun `Reminder watch-hint + interrogative routes to ListReminders`() {
        val r = route("is there a reminder at 2pm", watchHint = Intent.Reminder)
        assertEquals(Intent.ListReminders, r.result.intent)
    }

    @Test fun `Reminder watch-hint + confident Reschedule classifier routes to Reschedule`() {
        val r = route(
            "push my reminder to 6",
            watchHint = Intent.Reminder,
            classifier = FakeIntentClassifier { q -> classified(Intent.Reschedule, 0.7f, q) },
        )
        assertEquals(Intent.Reschedule, r.result.intent)
    }

    @Test fun `Reminder watch-hint without override stays on Reminder`() {
        val r = route("remind me at 5pm to call mom", watchHint = Intent.Reminder)
        assertEquals(Intent.Reminder, r.result.intent)
    }

    // ── Confident classifier above threshold is trusted ─────────────────

    @Test fun `confident classifier above threshold is trusted`() {
        // No watch hint, no PrefixHints match — confidence > 0.55 with
        // no close alternates should yield the classifier's pick directly.
        val r = route(
            "show me the dashboard",
            classifier = FakeIntentClassifier { q -> classified(Intent.OpenApp, 0.8f, q) },
        )
        assertEquals(Intent.OpenApp, r.result.intent)
    }

    // ── PrefixHints WorldTime override (Time / Unknown → WorldTime) ────

    @Test fun `time-in-Tokyo routes to WorldTime via PrefixHints`() {
        val r = route("what time is it in Tokyo")
        assertEquals(Intent.WorldTime, r.result.intent)
    }

    // ── Unmatched query stays Unknown ──────────────────────────────────

    @Test fun `unmatched query stays Unknown`() {
        // No classifier match, no watch hint, no PrefixHints rule.
        // Phrase choice matters — see feedback_nlu_assertNull_phrase_decay.
        val r = route("tell me a joke")
        assertEquals(Intent.Unknown, r.result.intent)
        assertTrue(r.result.slots.isEmpty())
    }

    // ── classified field preserves pre-refinement intent ───────────────

    @Test fun `classified preserves classifier pick when result is refined`() {
        // Classifier confidently picked Reminder; WatchHintRefiner refined
        // to ListReminders. The Routed.classified field keeps the
        // pre-refinement pick for downstream log lines.
        val r = route(
            "is there a reminder at 2pm",
            watchHint = Intent.Reminder,
            classifier = FakeIntentClassifier { q -> classified(Intent.Reminder, 0.7f, q) },
        )
        assertEquals(Intent.ListReminders, r.result.intent)
        assertEquals(Intent.Reminder, r.classified?.intent)
        assertEquals(0.7f, r.classified?.confidence)
    }
}
