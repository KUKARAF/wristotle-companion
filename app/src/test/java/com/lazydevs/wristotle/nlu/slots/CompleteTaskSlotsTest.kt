package com.lazydevs.wristotle.nlu.slots

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompleteTaskSlotsTest {

    private fun extract(query: String): Map<String, Any> =
        runBlocking { CompleteTaskSlots().extract(query) }

    @Test fun stripsCompleteVerb() {
        assertEquals("buy milk", extract("complete buy milk")["target"])
    }

    @Test fun stripsFinishVerb() {
        assertEquals("buy groceries", extract("finish buy groceries")["target"])
    }

    @Test fun stripsMarkAsDone() {
        // Verb prefix + trailing "as done" both stripped.
        assertEquals("buy milk", extract("mark buy milk as done")["target"])
    }

    @Test fun stripsBareDoneTrailingModifier() {
        // "mark X done" — bare trailing past-tense modifier.
        assertEquals("buy milk", extract("mark buy milk done")["target"])
    }

    @Test fun stripsCheckOff() {
        assertEquals("call the dentist", extract("check off call the dentist")["target"])
    }

    @Test fun stripsDoneWith() {
        // "done with the laundry" — the regex consumes "done with" AND
        // the optional "the" filler, leaving the bare body.
        assertEquals("laundry", extract("done with the laundry")["target"])
    }

    @Test fun stripsDeleteVerb() {
        assertEquals("buy milk", extract("delete buy milk")["target"])
    }

    @Test fun stripsRemoveVerb_withTrailingFromMyTasks() {
        assertEquals("buy milk", extract("remove buy milk from my tasks")["target"])
    }

    @Test fun stripsTaskKeywordAfterVerb() {
        assertEquals("buy milk", extract("delete task buy milk")["target"])
    }

    @Test fun lastTaskShortcut_preservedAsTarget() {
        // Slot extractor doesn't resolve the shortcut — it just returns
        // the raw "last task" string. TaskMatching.resolve recognises
        // it at dispatch time. Trailing "done" is stripped (so the
        // shortcut keyword stays exact and matches LAST_TASK_KEYWORDS).
        assertEquals("last task", extract("complete the last task")["target"])
        assertEquals("latest task", extract("mark the latest task done")["target"])
    }

    @Test fun bareVerbWithoutBodyReturnsVerbAsTarget() {
        // "complete" alone (no body) doesn't match the strip regex
        // (it requires a trailing whitespace + body), so the verb
        // itself becomes the target. Handler will then return "No
        // task matching 'complete'" — acceptable for a malformed
        // query the user is unlikely to repeat.
        assertEquals("complete", extract("complete")["target"])
    }
}
