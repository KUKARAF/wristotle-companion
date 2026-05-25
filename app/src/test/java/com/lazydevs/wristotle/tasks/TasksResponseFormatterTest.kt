package com.lazydevs.wristotle.tasks

import org.junit.Assert.assertEquals
import org.junit.Test

class TasksResponseFormatterTest {

    @Test fun format_emptyReturnsDefaultMessage() {
        assertEquals("No tasks", TasksResponseFormatter.format(emptyList()))
    }

    @Test fun format_emptyUsesCustomEmptyMessage() {
        assertEquals(
            "No completed tasks",
            TasksResponseFormatter.format(emptyList(), emptyMessage = "No completed tasks"),
        )
    }

    @Test fun format_singleTask() {
        val s = TasksResponseFormatter.format(listOf(task(id = 1, text = "buy milk")))
        assertEquals("1. buy milk", s)
    }

    @Test fun format_multipleTasks_numbered() {
        val s = TasksResponseFormatter.format(listOf(
            task(id = 1, text = "buy milk"),
            task(id = 2, text = "call dentist"),
            task(id = 3, text = "fix the sink"),
        ))
        assertEquals("1. buy milk\n2. call dentist\n3. fix the sink", s)
    }

    @Test fun format_truncatesPastMaxInline_withPlusNMoreSuffix() {
        // Build MAX_INLINE+2 tasks; the formatter should show MAX_INLINE
        // numbered and a "(+2 more)" suffix.
        val n = TasksResponseFormatter.MAX_INLINE + 2
        val all = (1..n).map { task(id = it.toLong(), text = "task-$it") }
        val s = TasksResponseFormatter.format(all)
        val lines = s.split("\n")
        assertEquals(TasksResponseFormatter.MAX_INLINE + 1, lines.size)
        assertEquals("(+2 more)", lines.last())
        // Spot-check first + last numbered lines.
        assertEquals("1. task-1", lines.first())
        assertEquals("${TasksResponseFormatter.MAX_INLINE}. task-${TasksResponseFormatter.MAX_INLINE}",
                     lines[TasksResponseFormatter.MAX_INLINE - 1])
    }

    private fun task(id: Long, text: String) = TaskEntity(
        id = id,
        text = text,
        completed = false,
        createdAtEpochMs = 0,
        completedAtEpochMs = null,
        source = "test",
    )
}
