// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.tasks

import com.lazydevs.wristotle.speech.nlu.transport.MessageKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TasksWireFrameTest {

    @Test fun encode_decode_roundTripsSingleTask() {
        val tasks = listOf(task(id = 7, text = "buy milk", completed = false))
        val payload = TasksWireFrame.encode(tasks)
        val decoded = TasksWireFrame.decode(payload)
        assertEquals(1, decoded.size)
        assertEquals(7L, decoded[0].id)
        assertEquals("buy milk", decoded[0].text)
        assertEquals(false, decoded[0].completed)
    }

    @Test fun encode_includesStateField_perTask() {
        val payload = TasksWireFrame.encode(listOf(
            task(id = 1, text = "buy milk", completed = false),
            task(id = 2, text = "call dentist", completed = true),
        ))
        val decoded = TasksWireFrame.decode(payload)
        assertEquals(2, decoded.size)
        assertEquals(false, decoded[0].completed)
        assertEquals(true,  decoded[1].completed)
    }

    @Test fun encode_useUSAndRSSeparators() {
        val payload = TasksWireFrame.encode(listOf(
            task(id = 1, text = "a"),
            task(id = 2, text = "b"),
        ))
        // Two tasks → exactly two unit separators per task + one record
        // separator between them. Spot-check the raw character counts so a
        // future refactor that flips to a different separator gets caught.
        assertEquals(2, payload.count { it == MessageKeys.TASKS_RECORD_SEPARATOR } + 1)
        assertEquals(4, payload.count { it == MessageKeys.TASKS_UNIT_SEPARATOR })
    }

    @Test fun encode_capsAtBudget_dropsTrailingTasks() {
        // Build a long list that overflows the budget. Use a small budget
        // so the test runs fast.
        val many = (1..50).map { task(id = it.toLong(), text = "task-$it") }
        val payload = TasksWireFrame.encode(many, budget = 40)
        val decoded = TasksWireFrame.decode(payload)
        assertTrue("budget must drop some tasks; got ${decoded.size} of 50",
                   decoded.size < 50)
        // Encoded payload should NOT exceed the budget.
        assertTrue("payload ${payload.length} <= budget 40", payload.length <= 40)
    }

    @Test fun decode_emptyPayloadReturnsEmptyList() {
        assertEquals(emptyList<TasksWireFrame.Decoded>(), TasksWireFrame.decode(""))
    }

    @Test fun decode_skipsMalformedRecords() {
        // Malformed: missing one of the unit separators. Decoder should
        // skip silently rather than throw.
        val good = "1${MessageKeys.TASKS_UNIT_SEPARATOR}0${MessageKeys.TASKS_UNIT_SEPARATOR}good"
        val bad  = "noseps"
        val payload = "$good${MessageKeys.TASKS_RECORD_SEPARATOR}$bad"
        val decoded = TasksWireFrame.decode(payload)
        assertEquals(1, decoded.size)
        assertEquals("good", decoded[0].text)
    }

    @Test fun decode_skipsRowWithNonNumericId() {
        val payload = "abc${MessageKeys.TASKS_UNIT_SEPARATOR}0${MessageKeys.TASKS_UNIT_SEPARATOR}weird"
        val decoded = TasksWireFrame.decode(payload)
        assertEquals(0, decoded.size)
    }

    private fun task(id: Long, text: String, completed: Boolean = false) = TaskEntity(
        id = id,
        text = text,
        completed = completed,
        createdAtEpochMs = 0,
        completedAtEpochMs = if (completed) 0 else null,
        source = "test",
    )
}