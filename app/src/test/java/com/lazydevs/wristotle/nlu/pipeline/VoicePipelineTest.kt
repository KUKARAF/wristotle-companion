// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.nlu.pipeline

import com.lazydevs.wristotle.speech.nlu.VoicePipeline
import com.lazydevs.wristotle.speech.nlu.slots.SlotKeys
import com.lazydevs.wristotle.speech.nlu.slots.*
import com.lazydevs.wristotle.phone.ContactsRepository
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.PrefixHints
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end voice routing tests — query in, (intent, slots) out, no
 * Android / PebbleKit / Whisper in the loop. Each row exercises the
 * full classifier → [com.lazydevs.wristotle.speech.nlu.WatchHintRefiner] →
 * slot-extractor seam. See `tests.md` for how to add a row.
 */
class VoicePipelineTest {

    private fun route(
        query: String,
        watchHint: Intent? = null,
        classifier: FakeIntentClassifier = FakeIntentClassifier(),
        askAgentSubjects: List<String> = emptyList(),
        findContact: suspend (String) -> ContactsRepository.Contact? = { null },
    ): VoicePipeline.Routed = runBlocking {
        val subjectsProvider = { askAgentSubjects }
        VoicePipeline(
            classifier = classifier,
            slotExtractors = testSlotRegistry(
                findContact = findContact,
                askAgentSubjects = subjectsProvider,
            ),
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

    // ── PrefixHints rescue from below-threshold classifier ─────────────

    @Test fun `below-threshold classifier is rescued by PrefixHints`() {
        // Classifier confidently mis-picked Weather; confidence 0.30 is
        // below ROUTE_THRESHOLD (0.55) so refineUnhinted falls back to
        // PrefixHints, which catches "ask agent" → AskAgent.
        val r = route(
            "ask agent what's the capital of France",
            classifier = FakeIntentClassifier { q -> classified(Intent.Weather, 0.30f, q) },
        )
        assertEquals(Intent.AskAgent, r.result.intent)
        // pre-refinement intent (Weather) preserved for log lines.
        assertEquals(Intent.Weather, r.classified?.intent)
    }

    // ── PrefixHints rescue from tight margin ────────────────────────────

    @Test fun `tight-margin classifier is rescued by PrefixHints`() {
        // Above threshold (0.62) but runner-up is within ROUTE_MARGIN
        // (0.10) → the gate flags this ambiguous and asks PrefixHints.
        // Same query "ask agent …" lights up the AskAgent rule.
        val r = route(
            "ask agent what time is it in Tokyo",
            classifier = FakeIntentClassifier { q ->
                classified(Intent.WorldTime, 0.62f, q, runnerUp = Intent.Weather to 0.58f)
            },
        )
        assertEquals(Intent.AskAgent, r.result.intent)
    }

    @Test fun `confident classifier with clear margin is NOT rescued`() {
        // Opposite of the rescue case — same query but the classifier is
        // confident AND the runner-up is far behind (margin 0.30 > 0.10),
        // so PrefixHints is never consulted and the (wrong) classifier
        // pick wins. Documents the gate's actual contract: PrefixHints
        // only fires when the classifier is uncertain.
        val r = route(
            "ask agent what time is it in Tokyo",
            classifier = FakeIntentClassifier { q ->
                classified(Intent.WorldTime, 0.80f, q, runnerUp = Intent.Weather to 0.50f)
            },
        )
        assertNotEquals(Intent.AskAgent, r.result.intent)
        assertEquals(Intent.WorldTime, r.result.intent)
    }

    // ── Cancel watch-hint passes through ───────────────────────────────

    @Test fun `Cancel watch-hint routes to Cancel with target slot`() {
        // The watch tags any query containing "cancel" as CANCEL_QUERY.
        // No refinement within the Cancel family — the hint passes
        // through, and CancelSlots extracts the target reminder.
        val r = route("cancel my 2pm reminder", watchHint = Intent.Cancel)
        assertEquals(Intent.Cancel, r.result.intent)
        assertNotNull("expected target slot", r.result.slots[SlotKeys.Target])
    }

    // ── Slot payload: Reminder title strip ─────────────────────────────

    @Test fun `Reminder title is captured and time clause stripped`() {
        // Watch routes "remind" queries with REMINDER_QUERY hint.
        // ReminderSlots strips the "remind me to" lead-in AND the trailing
        // time clause to leave a clean title.
        val r = route("remind me to call mom at 2pm", watchHint = Intent.Reminder)
        assertEquals(Intent.Reminder, r.result.intent)
        assertEquals("Call mom", r.result.slots[SlotKeys.Title])
    }

    // ── Slot payload: CreateEvent attendee + title coexist ─────────────

    @Test fun `CreateEvent populates both attendee and title slots`() {
        // The v0.15.4 fix: "with Alex" used to clobber a separately-
        // specified title. Both slots should appear; the handler joins
        // them at dispatch time into "Standup with Alex".
        val r = route("schedule a meeting with Alex called standup tomorrow at 3pm")
        assertEquals(Intent.CreateEvent, r.result.intent)
        assertEquals("Alex", r.result.slots[SlotKeys.Attendee])
        assertEquals("Standup", r.result.slots[SlotKeys.Title])
    }

    // ── Slot payload: SendMessage contact resolution flows through ─────

    @Test fun `SendMessage uses findContact to confirm contact boundary`() {
        // SendMessageSlots calls findContact() to verify the candidate
        // word ("mom") is a real contact — that's how it knows the
        // contact ends after one word and the body starts. This row
        // proves the findContact lambda plumbed through testSlotRegistry
        // actually reaches the slot extractor.
        val r = route(
            "WhatsApp mom on my way",
            findContact = { name ->
                if (name.lowercase() == "mom") {
                    ContactsRepository.Contact(name = "Mom", number = "555-0100")
                } else null
            },
        )
        assertEquals(Intent.SendMessage, r.result.intent)
        assertEquals("WhatsApp", r.result.slots[SlotKeys.App])
        assertEquals("mom", r.result.slots[SlotKeys.Contact])
        assertEquals("on my way", r.result.slots[SlotKeys.Body])
    }

    // ── Slot payload: Calculator expression ────────────────────────────

    @Test fun `Calculator expression is rendered as arithmetic`() {
        // CalculateSlots translates spoken operator words ("plus") into
        // arithmetic syntax for the recursive-descent Calculator.
        val r = route("what's 25 plus 17")
        assertEquals(Intent.Calculate, r.result.intent)
        assertEquals("25 + 17", r.result.slots[SlotKeys.Expression])
    }

    @Test fun `Calculator percent-of expands to parenthesised expression`() {
        // "15% of 80" must expand to (15 / 100 * 80) so the calculator
        // honours order-of-operations the way humans expect.
        val r = route("what's 15% of 80")
        assertEquals(Intent.Calculate, r.result.intent)
        assertEquals("( 15 / 100 * 80 )", r.result.slots[SlotKeys.Expression])
    }

    // ── Slot payload: Weather location ─────────────────────────────────

    @Test fun `Weather location is captured`() {
        val r = route("weather in tokyo")
        assertEquals(Intent.Weather, r.result.intent)
        assertEquals("tokyo", r.result.slots[SlotKeys.Location])
    }

    @Test fun `Weather without location yields no location slot`() {
        // Bare "what's the weather" falls back to current-location on the
        // handler side; the slot extractor leaves the location blank.
        val r = route("what's the weather")
        assertEquals(Intent.Weather, r.result.intent)
        assertFalse(
            "should not carry a location slot",
            r.result.slots.containsKey(SlotKeys.Location),
        )
    }

    // ── Slot payload: WorldTime location ───────────────────────────────

    @Test fun `WorldTime location is captured for in-city queries`() {
        val r = route("what time is it in Tokyo")
        assertEquals(Intent.WorldTime, r.result.intent)
        assertEquals("tokyo", r.result.slots[SlotKeys.Location])
    }

    // ── Stub classifier exposes isStubClassifier ───────────────────────

    @Test fun `stub classifier propagates to isStubClassifier`() {
        // PebbleListenerService's "no NLU model loaded" branch reads
        // this flag through the pipeline. A rename in the stub
        // implementation must not silently disable the hint.
        val pipeline = VoicePipeline(
            classifier = FakeIntentClassifier(isStub = true),
            slotExtractors = testSlotRegistry(),
        )
        assertTrue(pipeline.isStubClassifier)
    }

    @Test fun `non-stub classifier reports isStubClassifier false`() {
        val pipeline = VoicePipeline(
            classifier = FakeIntentClassifier(isStub = false),
            slotExtractors = testSlotRegistry(),
        )
        assertFalse(pipeline.isStubClassifier)
    }
}