// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.slots

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Filter detection for ListTasks. Three outcomes (completed / pending /
 * default-empty) driven by two keyword regexes, incl. the trailing-verb form
 * ("tasks I've completed"). A regex edit could silently flip "done tasks" to
 * pending or drop the trailing-verb shape; these pin it.
 */
class ListTasksSlotsTest {

    private val slots = ListTasksSlots()
    private suspend fun filter(q: String): Any? = slots.extract(q)[SlotKeys.Filter]

    @Test fun completedAdjectiveForms() = runTest {
        assertEquals(SlotKeys.FilterCompleted, filter("completed tasks"))
        assertEquals(SlotKeys.FilterCompleted, filter("show me my done tasks"))
        assertEquals(SlotKeys.FilterCompleted, filter("finished tasks"))
    }

    @Test fun completedTrailingVerbForms() = runTest {
        assertEquals(SlotKeys.FilterCompleted, filter("tasks i've completed"))
        assertEquals(SlotKeys.FilterCompleted, filter("tasks i have done"))
    }

    @Test fun pendingForms() = runTest {
        assertEquals(SlotKeys.FilterPending, filter("pending tasks"))
        assertEquals(SlotKeys.FilterPending, filter("open tasks"))
        assertEquals(SlotKeys.FilterPending, filter("remaining tasks"))
        assertEquals(SlotKeys.FilterPending, filter("outstanding tasks"))
    }

    @Test fun bareListsDefaultToEmpty() = runTest {
        // No filter keyword → empty map; the handler defaults to pending.
        assertTrue(slots.extract("list my tasks").isEmpty())
        assertTrue(slots.extract("what are my tasks").isEmpty())
        assertTrue(slots.extract("all tasks").isEmpty())
    }
}
