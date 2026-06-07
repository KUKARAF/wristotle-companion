// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

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

    // --- prunePastDue ---

    @Test fun prunePastDueDropsOnlyBeyondRetentionWindow() {
        val now = 100_000_000L
        val day = 24L * 60 * 60 * 1000
        val records = listOf(
            ReminderRecord("old", "fired yesterday", now - day - 1),  // beyond window → dropped
            ReminderRecord("recent", "fired 1s ago", now - 1000),     // within window → kept
            ReminderRecord("future", "later", now + 1000),            // future → kept
            ReminderRecord("notime", "legacy", null),                 // unknown → kept
        )
        val pruned = PinStoreCodec.prunePastDue(records, now)
        assertEquals(listOf("recent", "future", "notime"), pruned.map { it.id })
    }

    @Test fun framedRecordWithEmptyIdIsSkipped() {
        // A framed record whose id field is empty must not produce a ghost entry.
        // encode() inserts the record separator so the framing stays valid.
        val raw = PinStoreCodec.encode(
            listOf(ReminderRecord("", "ghost", 1L), ReminderRecord("real", "ok", 5L)),
        )
        assertEquals(listOf("real"), PinStoreCodec.decode(raw).map { it.id })
    }

    // --- persistent reminder fields (added in companion v1.5.0) ---

    @Test fun persistentFieldsRoundTrip() {
        val records = listOf(
            ReminderRecord("p1", "Take meds", 1_000L, isPersistent = true, attemptsRemaining = 5),
            ReminderRecord("p2", "Stretch", 2_000L, isPersistent = true, attemptsRemaining = 1),
        )
        assertEquals(records, PinStoreCodec.decode(PinStoreCodec.encode(records)))
    }

    @Test fun nonPersistentRecordDecodesWithDefaults() {
        // A reminder saved before the persistent fields existed (3-field wire)
        // must decode back as non-persistent with zero attempts.
        // Constructing the wire by hand mimics an upgrade-from-old-blob path.
        val raw = "old-idbuy milk1700000000000"  // 3 fields, no persistent suffix
        val decoded = PinStoreCodec.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals(false, decoded[0].isPersistent)
        assertEquals(0, decoded[0].attemptsRemaining)
    }

    @Test fun mixedPersistentAndPlainRoundTripInOrder() {
        val records = listOf(
            ReminderRecord("plain", "Call dentist", 100L),
            ReminderRecord("nag", "Take meds", 200L, isPersistent = true, attemptsRemaining = 3),
            ReminderRecord("plain-no-time", "Standup", null),
        )
        assertEquals(records, PinStoreCodec.decode(PinStoreCodec.encode(records)))
    }
}