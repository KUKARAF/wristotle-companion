package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.phone.ContactsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendMessageSlotsTest {

    private fun extractor(knownContacts: Set<String> = emptySet()) =
        SendMessageSlots(findContact = { query ->
            if (query.lowercase() in knownContacts.map(String::lowercase))
                ContactsRepository.Contact(name = query, number = "555-0100")
            else null
        })

    private fun extract(query: String, contacts: Set<String> = setOf("mom", "dad", "alex", "john smith")): Map<String, Any> =
        runBlocking { extractor(contacts).extract(query) }

    // ── App-first phrasing ─────────────────────────────────────────────

    @Test fun `whatsapp app-first with single-word contact`() {
        val s = extract("WhatsApp mom on my way")
        assertEquals("WhatsApp", s["app"])
        assertEquals("mom", s["contact"])
        assertEquals("on my way", s["body"])
    }

    @Test fun `telegram app-first with single-word contact`() {
        val s = extract("Telegram dad I'll be late")
        assertEquals("Telegram", s["app"])
        assertEquals("dad", s["contact"])
        assertEquals("i'll be late", s["body"])
    }

    @Test fun `signal app-first with single-word contact`() {
        val s = extract("Signal alex meeting moved")
        assertEquals("Signal", s["app"])
        assertEquals("alex", s["contact"])
        assertEquals("meeting moved", s["body"])
    }

    @Test fun `multi-word contact resolves greedily`() {
        // Without greedy lookup, "WhatsApp John Smith hi" would pick
        // contact="John", body="Smith hi". The greedy walk catches the
        // 2-word prefix when ContactsRepository has it.
        val s = extract("WhatsApp John Smith hi", setOf("john smith"))
        assertEquals("WhatsApp", s["app"])
        assertEquals("john smith", s["contact"])
        assertEquals("hi", s["body"])
    }

    @Test fun `whisper variant whats app maps to WhatsApp`() {
        // Whisper renders "WhatsApp" with a space about half the time.
        val s = extract("whats app mom on my way")
        assertEquals("WhatsApp", s["app"])
        assertEquals("mom", s["contact"])
        assertEquals("on my way", s["body"])
    }

    // ── Verb + "on <app>" phrasing ─────────────────────────────────────

    @Test fun `text contact on whatsapp body`() {
        val s = extract("text mom on whatsapp on my way")
        assertEquals("WhatsApp", s["app"])
        assertEquals("mom", s["contact"])
        assertEquals("on my way", s["body"])
    }

    @Test fun `message contact on telegram body`() {
        val s = extract("message dad on telegram see you soon")
        assertEquals("Telegram", s["app"])
        assertEquals("dad", s["contact"])
        assertEquals("see you soon", s["body"])
    }

    @Test fun `send a whatsapp message to contact body`() {
        val s = extract("send a whatsapp message to mom on my way")
        assertEquals("WhatsApp", s["app"])
        assertEquals("mom", s["contact"])
        assertEquals("on my way", s["body"])
    }

    @Test fun `send a signal message to contact body`() {
        val s = extract("send a signal message to alex meeting moved")
        assertEquals("Signal", s["app"])
        assertEquals("alex", s["contact"])
        assertEquals("meeting moved", s["body"])
    }

    // ── Failure modes ──────────────────────────────────────────────────

    @Test fun `unrecognised app returns empty`() {
        val s = extract("Skype mom hi")
        assertTrue("expected empty for unsupported app, got $s", s.isEmpty())
    }

    @Test fun `empty query returns empty`() {
        val s = extract("")
        assertTrue(s.isEmpty())
    }

    @Test fun `app-first without body falls back to contact-only or empty`() {
        // "WhatsApp mom" alone — no body. With "mom" a known contact, we
        // expect contact set but no body (matches Sms's fallback pattern).
        val s = extract("WhatsApp mom")
        // Either contact-only result OR empty are both acceptable here;
        // body absence is the load-bearing assertion.
        assertNull("body should be absent", s["body"])
    }

    // ── Whisper-comma after verb normalises ────────────────────────────

    @Test fun `comma after WhatsApp is normalised`() {
        val s = extract("WhatsApp, mom on my way")
        assertEquals("WhatsApp", s["app"])
        assertEquals("mom", s["contact"])
        assertEquals("on my way", s["body"])
    }
}
