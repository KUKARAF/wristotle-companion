package com.lazydevs.wristotle.backup

import com.lazydevs.wristotle.tasks.TaskEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskJsonTest {

    @Test fun encode_decode_roundTripsPendingTask() {
        val original = TaskEntity(
            id = 7,
            text = "buy milk",
            completed = false,
            createdAtEpochMs = 1700000000000,
            completedAtEpochMs = null,
            source = "watch",
        )
        val decoded = TaskJson.decode(TaskJson.encode(original), TaskJson.CURRENT_SCHEMA)
        assertEquals(original, decoded)
    }

    @Test fun encode_decode_roundTripsCompletedTask() {
        val original = TaskEntity(
            id = 11,
            text = "call dentist",
            completed = true,
            createdAtEpochMs = 1700000000000,
            completedAtEpochMs = 1700000300000,
            source = "watch",
        )
        val decoded = TaskJson.decode(TaskJson.encode(original), TaskJson.CURRENT_SCHEMA)
        assertEquals(original, decoded)
    }

    @Test fun encode_omits_completedAtForPendingTask() {
        val pending = TaskEntity(
            id = 1, text = "x", completed = false,
            createdAtEpochMs = 0, completedAtEpochMs = null, source = "test",
        )
        val json = TaskJson.encode(pending)
        assertFalse("pending task must not carry completed_at_ms", json.has("completed_at_ms"))
    }

    @Test fun decode_acceptsMissingOptionalFields() {
        val minimal = JSONObject("""{"id":5,"text":"abc","created_at_ms":42}""")
        val decoded = TaskJson.decode(minimal, schema = 1)
        assertEquals(5L, decoded.id)
        assertEquals("abc", decoded.text)
        assertEquals(42L, decoded.createdAtEpochMs)
        assertFalse(decoded.completed)
        assertNull(decoded.completedAtEpochMs)
        assertEquals("unknown", decoded.source)
    }

    @Test fun decode_rejectsFutureSchema() {
        val row = JSONObject("""{"id":1,"text":"x"}""")
        val ex = assertThrows(IllegalArgumentException::class.java) {
            TaskJson.decode(row, schema = TaskJson.CURRENT_SCHEMA + 1)
        }
        assertTrue(
            "exception message must name TaskJson + the unsupported schema",
            ex.message!!.contains("TaskJson") && ex.message!!.contains("${TaskJson.CURRENT_SCHEMA + 1}"),
        )
    }
}
