package com.lazydevs.wristotle.nlu.slots

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddTaskSlotsTest {

    private fun extract(query: String): Map<String, Any> =
        runBlocking { AddTaskSlots().extract(query) }

    @Test fun stripsAddTaskVerb() {
        assertEquals("Buy milk", extract("add task buy milk")["body"])
    }

    @Test fun stripsNewTaskVerb() {
        assertEquals("Call the dentist", extract("new task call the dentist")["body"])
    }

    @Test fun stripsCreateTaskVerb() {
        assertEquals("Plan the weekend trip", extract("create a task plan the weekend trip")["body"])
    }

    @Test fun stripsBareTaskOpener() {
        assertEquals("Drop off the dry cleaning", extract("task drop off the dry cleaning")["body"])
    }

    @Test fun handlesPluralisedTask_WhisperVariant() {
        // Whisper routinely mis-pluralises mid-utterance ("add task" →
        // "add tasks"). Slot extractor accepts either form.
        assertEquals("Buy oysters", extract("add tasks buy oysters")["body"])
    }

    @Test fun stripsLeadingConnector_after_task_verb() {
        // "add task TO buy milk" — the optional connector after the verb
        // should be stripped so the body reads as a clean sentence.
        assertEquals("Buy milk", extract("add task to buy milk")["body"])
        assertEquals("I need to call mom", extract("add task that I need to call mom")["body"])
    }

    @Test fun handlesLeadingNounShape() {
        // "add to my tasks X" — the noun appears BEFORE the body
        // (Whisper sometimes inverts the trailing-noun shape).
        assertEquals("Buy oysters", extract("add to my tasks buy oysters")["body"])
        assertEquals(
            "Call the dentist",
            extract("add to my to-do list call the dentist")["body"],
        )
    }

    @Test fun handlesTrailingNounShape() {
        // "add X to my tasks" — verb at front, noun at the end.
        assertEquals("Buy milk", extract("add buy milk to my tasks")["body"])
        assertEquals("Email the team", extract("put email the team on my tasks")["body"])
    }

    @Test fun bodyIsCapitalised() {
        assertEquals("Lowercase first", extract("add task lowercase first")["body"])
    }

    @Test fun emptyQueryReturnsEmpty() {
        assertTrue(extract("").isEmpty())
    }

    @Test fun bareVerbReturnsTaskNounAsBody() {
        // "add task" alone (no body) — the bare `add\b` alternative
        // strips just the verb, leaving " task". Trim + capitalise →
        // "Task" as the body. Not great UX for a malformed query, but
        // matches how every other slot extractor degrades. Handler's
        // empty-body check would catch a truly blank query first.
        assertEquals("Task", extract("add task")["body"])
    }
}
