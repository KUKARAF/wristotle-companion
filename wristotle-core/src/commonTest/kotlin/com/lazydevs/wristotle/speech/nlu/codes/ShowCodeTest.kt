// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.codes

import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.speech.nlu.slots.ShowCodeSlots
import com.lazydevs.wristotle.speech.nlu.slots.SlotKeys
import com.lazydevs.wristotle.speech.nlu.transport.MessageKeys
import com.lazydevs.wristotle.speech.nlu.transport.ReminderPin
import com.lazydevs.wristotle.speech.nlu.transport.TimelineSendResult
import com.lazydevs.wristotle.speech.nlu.transport.WatchTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShowCodeTest {

    @Test fun `slots strip lead-in and trailing noun, bare list is empty`() = runTest {
        val s = ShowCodeSlots()
        assertEquals("tesco", s.extract("show my tesco card")[SlotKeys.Subject])
        assertEquals("clubcard", s.extract("pull up my clubcard")[SlotKeys.Subject])
        assertEquals("gym", s.extract("show the gym pass")[SlotKeys.Subject])
        // dictation trailing period must not defeat the noun strip (the live bug);
        // case is preserved (the handler lowercases when matching)
        assertEquals("QR", s.extract("Show QR card.")[SlotKeys.Subject])
        // a bare format word survives as a hint instead of stripping to empty
        assertEquals("barcode", s.extract("show my barcode")[SlotKeys.Subject])
        assertTrue(s.extract("show my codes").isEmpty())
    }

    @Test fun `handler matches by format when no name fits`() = runTest {
        val codes = listOf(
            SavedCode("a", "Clubcard", "tesco", CodeFormat.CODE_128, "123456789012", 0L),
            SavedCode("b", "Wristotle", "", CodeFormat.QR_CODE, "https://wristotle.app", 0L),
        )
        val tx = FakeTransport()
        ShowCodeHandler(FakeRepo(codes), tx).handle(result("qr"))
        assertEquals(1, tx.shownIndex)   // "qr" → the QR_CODE entry
        ShowCodeHandler(FakeRepo(codes), tx).handle(result("barcode"))
        assertEquals(0, tx.shownIndex)   // "barcode" → the 1D entry
    }

    @Test fun `handler resolves alias to the synced index and renders it`() = runTest {
        val codes = listOf(
            SavedCode("a", "Gym", "gym", CodeFormat.QR_CODE, "GYM123", 0L),
            SavedCode("b", "Tesco Clubcard", "tesco", CodeFormat.CODE_128, "123456789012", 0L),
        )
        val tx = FakeTransport()
        val resp = ShowCodeHandler(FakeRepo(codes), tx).handle(result("tesco"))
        assertEquals(1, tx.shownIndex)   // index 1 == its position in the encodable list == watch cache
        assertTrue(resp.contains("Tesco"))
    }

    @Test fun `multiple matches ask instead of guessing`() = runTest {
        val codes = listOf(
            SavedCode("a", "Tesco", "", CodeFormat.QR_CODE, "https://a", 0L),
            SavedCode("b", "Boots", "", CodeFormat.QR_CODE, "https://b", 0L),
        )
        val tx = FakeTransport()
        val resp = ShowCodeHandler(FakeRepo(codes), tx).handle(result("qr"))
        assertEquals(-1, tx.shownIndex)  // two QRs → didn't render either
        assertTrue(resp.contains("Tesco") && resp.contains("Boots"))
    }

    @Test fun `handler with an unknown subject sends nothing`() = runTest {
        val tx = FakeTransport()
        val resp = ShowCodeHandler(
            FakeRepo(listOf(SavedCode("a", "Gym", "gym", CodeFormat.QR_CODE, "GYM123", 0L))), tx,
        ).handle(result("nonsense"))
        assertEquals(-1, tx.shownIndex)
        assertTrue(resp.contains("Couldn't find"))
    }

    private fun result(subject: String) = IntentResult(
        intent = Intent.ShowCode,
        slots = mapOf(SlotKeys.Subject to subject),
        confidence = 1f,
        alternates = emptyList(),
        rawQuery = "show my $subject",
    )

    private class FakeRepo(private val codes: List<SavedCode>) : CodeRepository {
        override fun observeAll(): Flow<List<SavedCode>> = flowOf(codes)
        override suspend fun all() = codes
        override suspend fun add(label: String, alias: String, format: CodeFormat, data: String) =
            throw NotImplementedError()
        override suspend fun update(id: String, label: String, alias: String, format: CodeFormat, data: String) {}
        override suspend fun delete(id: String) {}
    }

    private class FakeTransport : WatchTransport {
        var shownIndex = -1
        override suspend fun sendInt32(key: UInt, value: Int): Boolean {
            if (key == MessageKeys.SHOW_CODE_INDEX) shownIndex = value
            return true
        }
        override suspend fun sendText(key: UInt, text: String) = true
        override suspend fun sendTexts(texts: Map<UInt, String>) = true
        override suspend fun sendPresence(key: UInt) = true
        override suspend fun sendTtsChunk(bytes: ByteArray, start: Boolean, end: Boolean) = true
        override suspend fun sendCodeFrame(index: Int, count: Int, label: String, matrix: ByteArray) = true
        override suspend fun insertReminderPin(pin: ReminderPin) = TimelineSendResult.Success
        override suspend fun deleteReminderPin(pinId: String) = TimelineSendResult.Success
    }
}
