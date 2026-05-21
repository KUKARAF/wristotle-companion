package com.lazydevs.wristotle.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinStoreCodecTest {

    @Test fun emptyDecodesToEmpty() {
        assertTrue(PinStoreCodec.decode("").isEmpty())
    }

    @Test fun singleRecordRoundTrips() {
        val records = listOf(ReminderRecord("id-1", "Take out the bins", 1_700_000_000_000L))
        assertEquals(records, PinStoreCodec.decode(PinStoreCodec.encode(records)))
    }

    @Test fun multipleRecordsRoundTripInOrder() {
        val records = listOf(
            ReminderRecord("a", "Call dentist", 100L),
            ReminderRecord("b", "Gym", 200L),
            ReminderRecord("c", "Standup", null),
        )
        assertEquals(records, PinStoreCodec.decode(PinStoreCodec.encode(records)))
    }

    @Test fun titleWithCommaAndColonSurvives() {
        // The old comma-joined format could not represent this.
        val records = listOf(ReminderRecord("id", "buy milk, eggs: and bread", 1L))
        assertEquals(records, PinStoreCodec.decode(PinStoreCodec.encode(records)))
    }

    @Test fun nullTimeRoundTrips() {
        val records = listOf(ReminderRecord("id", "no time", null))
        val decoded = PinStoreCodec.decode(PinStoreCodec.encode(records))
        assertEquals(1, decoded.size)
        assertNull(decoded[0].timeMs)
    }

    // --- legacy migration: old format was a comma-joined list of bare ids ---

    @Test fun legacySingleIdMigrates() {
        val decoded = PinStoreCodec.decode("pin-abc")
        assertEquals(1, decoded.size)
        assertEquals("pin-abc", decoded[0].id)
        assertEquals(PinStoreCodec.LEGACY_TITLE, decoded[0].title)
        assertNull(decoded[0].timeMs)
    }

    @Test fun legacyMultipleIdsMigrateInOrder() {
        val decoded = PinStoreCodec.decode("id1,id2,id3")
        assertEquals(listOf("id1", "id2", "id3"), decoded.map { it.id })
        assertTrue(decoded.all { it.timeMs == null && it.title == PinStoreCodec.LEGACY_TITLE })
    }

    @Test fun legacyBlanksAreSkipped() {
        val decoded = PinStoreCodec.decode("id1,,id2,")
        assertEquals(listOf("id1", "id2"), decoded.map { it.id })
    }

    @Test fun framedRecordWithEmptyIdIsSkipped() {
        // A framed record whose id field is empty must not produce a ghost entry.
        // encode() inserts the record separator so the framing stays valid.
        val raw = PinStoreCodec.encode(
            listOf(ReminderRecord("", "ghost", 1L), ReminderRecord("real", "ok", 5L)),
        )
        assertEquals(listOf("real"), PinStoreCodec.decode(raw).map { it.id })
    }
}
